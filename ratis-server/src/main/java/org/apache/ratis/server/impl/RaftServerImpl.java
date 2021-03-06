/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.server.impl;

import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.*;
import org.apache.ratis.protocol.*;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.server.RaftServerMXBean;
import org.apache.ratis.server.RaftServerRpc;
import org.apache.ratis.server.protocol.RaftServerAsynchronousProtocol;
import org.apache.ratis.server.protocol.RaftServerProtocol;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftLog;
import org.apache.ratis.server.storage.RaftStorageDirectory;
import org.apache.ratis.statemachine.SnapshotInfo;
import org.apache.ratis.statemachine.StateMachine;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.ratis.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto.AppendResult.INCONSISTENCY;
import static org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto.AppendResult.NOT_LEADER;
import static org.apache.ratis.proto.RaftProtos.AppendEntriesReplyProto.AppendResult.SUCCESS;
import static org.apache.ratis.util.LifeCycle.State.NEW;
import static org.apache.ratis.util.LifeCycle.State.RUNNING;
import static org.apache.ratis.util.LifeCycle.State.STARTING;

public class RaftServerImpl implements RaftServerProtocol, RaftServerAsynchronousProtocol,
    RaftClientProtocol, RaftClientAsynchronousProtocol {
  public static final Logger LOG = LoggerFactory.getLogger(RaftServerImpl.class);

  private static final String CLASS_NAME = RaftServerImpl.class.getSimpleName();
  static final String REQUEST_VOTE = CLASS_NAME + ".requestVote";
  static final String APPEND_ENTRIES = CLASS_NAME + ".appendEntries";
  static final String INSTALL_SNAPSHOT = CLASS_NAME + ".installSnapshot";

  private final RaftServerProxy proxy;
  private final StateMachine stateMachine;
  private final int minTimeoutMs;
  private final int maxTimeoutMs;
  private final int rpcSlownessTimeoutMs;
  private final boolean installSnapshotEnabled;

  private final LifeCycle lifeCycle;
  private final ServerState state;
  private final RaftGroupId groupId;
  private final Supplier<RaftPeer> peerSupplier = JavaUtils.memoize(() -> new RaftPeer(getId(), getServerRpc().getInetSocketAddress()));
  private final RoleInfo role;

  private final RetryCache retryCache;
  private final CommitInfoCache commitInfoCache = new CommitInfoCache();

  private final RaftServerJmxAdapter jmxAdapter;

  private AtomicReference<TermIndex> inProgressInstallSnapshotRequest;

  RaftServerImpl(RaftGroup group, StateMachine stateMachine, RaftServerProxy proxy) throws IOException {
    final RaftPeerId id = proxy.getId();
    LOG.info("{}: new RaftServerImpl for {} with {}", id, group, stateMachine);
    this.groupId = group.getGroupId();
    this.lifeCycle = new LifeCycle(id);
    this.stateMachine = stateMachine;
    this.role = new RoleInfo(id);

    final RaftProperties properties = proxy.getProperties();
    minTimeoutMs = RaftServerConfigKeys.Rpc.timeoutMin(properties).toIntExact(TimeUnit.MILLISECONDS);
    maxTimeoutMs = RaftServerConfigKeys.Rpc.timeoutMax(properties).toIntExact(TimeUnit.MILLISECONDS);
    rpcSlownessTimeoutMs = RaftServerConfigKeys.Rpc.slownessTimeout(properties).toIntExact(TimeUnit.MILLISECONDS);
    installSnapshotEnabled = RaftServerConfigKeys.Log.Appender.installSnapshotEnabled(properties);
    Preconditions.assertTrue(maxTimeoutMs > minTimeoutMs,
        "max timeout: %s, min timeout: %s", maxTimeoutMs, minTimeoutMs);
    this.proxy = proxy;

    this.state = new ServerState(id, group, properties, this, stateMachine);
    this.retryCache = initRetryCache(properties);
    this.inProgressInstallSnapshotRequest = new AtomicReference<>(null);

    this.jmxAdapter = new RaftServerJmxAdapter();
  }

  private RetryCache initRetryCache(RaftProperties prop) {
    final TimeDuration expireTime = RaftServerConfigKeys.RetryCache.expiryTime(prop);
    return new RetryCache(expireTime);
  }

  LogAppender newLogAppender(
      LeaderState state, RaftPeer peer, Timestamp lastRpcTime, long nextIndex,
      boolean attendVote) {
    final FollowerInfo f = new FollowerInfo(getId(), peer, lastRpcTime, nextIndex, attendVote, rpcSlownessTimeoutMs);
    return getProxy().getFactory().newLogAppender(this, state, f);
  }

  RaftPeer getPeer() {
    return peerSupplier.get();
  }

  int getMinTimeoutMs() {
    return minTimeoutMs;
  }

  int getMaxTimeoutMs() {
    return maxTimeoutMs;
  }

  int getRandomTimeoutMs() {
    return minTimeoutMs + ThreadLocalRandom.current().nextInt(
        maxTimeoutMs - minTimeoutMs + 1);
  }

  public RaftGroupId getGroupId() {
    return groupId;
  }

  public StateMachine getStateMachine() {
    return stateMachine;
  }

  @VisibleForTesting
  public RetryCache getRetryCache() {
    return retryCache;
  }

  public RaftServerProxy getProxy() {
    return proxy;
  }

  public RaftServerRpc getServerRpc() {
    return proxy.getServerRpc();
  }

  private void setRole(RaftPeerRole newRole, Object reason) {
    LOG.info("{}:{} changes role from {} to {} at term {} for {}",
        getId(), getGroupId(), this.role, newRole, state.getCurrentTerm(), reason);
    this.role.transitionRole(newRole);
  }

  boolean start() {
    if (!lifeCycle.compareAndTransition(NEW, STARTING)) {
      return false;
    }
    LOG.info("{}: start {}", getId(), groupId);
    RaftConfiguration conf = getRaftConf();
    if (conf != null && conf.contains(getId())) {
      LOG.debug("{} starts as a follower, conf={}", getId(), conf);
      startAsFollower();
    } else {
      LOG.debug("{} starts with initializing state, conf={}", getId(), conf);
      startInitializing();
    }

    registerMBean(getId(), getGroupId(), jmxAdapter, jmxAdapter);
    state.start();
    return true;
  }

  static boolean registerMBean(
      RaftPeerId id, RaftGroupId groupdId, RaftServerMXBean mBean, JmxRegister jmx) {
    final String prefix = "Ratis:service=RaftServer,group=" + groupdId + ",id=";
    final String registered = jmx.register(mBean, Arrays.asList(
        () -> prefix + id,
        () -> prefix + ObjectName.quote(id.toString())));
    return registered != null;
  }

  /**
   * The peer belongs to the current configuration, should start as a follower
   */
  private void startAsFollower() {
    setRole(RaftPeerRole.FOLLOWER, "startAsFollower");
    role.startFollowerState(this);
    lifeCycle.transition(RUNNING);
  }

  /**
   * The peer does not have any configuration (maybe it will later be included
   * in some configuration). Start still as a follower but will not vote or
   * start election.
   */
  private void startInitializing() {
    setRole(RaftPeerRole.FOLLOWER, "startInitializing");
    // do not start FollowerState
  }

  public ServerState getState() {
    return state;
  }

  public RaftPeerId getId() {
    return getState().getSelfId();
  }

  RoleInfo getRole() {
    return role;
  }

  RaftConfiguration getRaftConf() {
    return getState().getRaftConf();
  }

  RaftGroup getGroup() {
    return RaftGroup.valueOf(groupId, getRaftConf().getPeers());
  }

  void shutdown(boolean deleteDirectory) {
    lifeCycle.checkStateAndClose(() -> {
      LOG.info("{}: shutdown {}", getId(), groupId);
      try {
        jmxAdapter.unregister();
      } catch (Exception ignored) {
        LOG.warn("Failed to un-register RaftServer JMX bean for " + getId(), ignored);
      }
      try {
        role.shutdownFollowerState();
      } catch (Exception ignored) {
        LOG.warn("Failed to shutdown FollowerState for " + getId(), ignored);
      }
      try{
        role.shutdownLeaderElection();
      } catch (Exception ignored) {
        LOG.warn("Failed to shutdown LeaderElection for " + getId(), ignored);
      }
      try{
        role.shutdownLeaderState(true);
      } catch (Exception ignored) {
        LOG.warn("Failed to shutdown LeaderState monitor for " + getId(), ignored);
      }
      try{
        state.close();
      } catch (Exception ignored) {
        LOG.warn("Failed to close state for " + getId(), ignored);
      }
      if (deleteDirectory) {
        final RaftStorageDirectory dir = state.getStorage().getStorageDir();
        try {
          FileUtils.deleteFully(dir.getRoot());
        } catch(Exception ignored) {
          LOG.warn(getId() + ": Failed to remove RaftStorageDirectory " + dir, ignored);
        }
      }
    });
  }

  public boolean isAlive() {
    return !lifeCycle.getCurrentState().isClosingOrClosed();
  }

  public boolean isFollower() {
    return role.isFollower();
  }

  public boolean isCandidate() {
    return role.isCandidate();
  }

  public boolean isLeader() {
    return role.isLeader();
  }

  /**
   * Change the server state to Follower if this server is in a different role or force is true.
   * @param newTerm The new term.
   * @param force Force to start a new {@link FollowerState} even if this server is already a follower.
   * @return if the term/votedFor should be updated to the new term
   */
  private synchronized boolean changeToFollower(long newTerm, boolean force, Object reason) {
    final RaftPeerRole old = role.getCurrentRole();
    final boolean metadataUpdated = state.updateCurrentTerm(newTerm);

    if (old != RaftPeerRole.FOLLOWER || force) {
      setRole(RaftPeerRole.FOLLOWER, reason);
      if (old == RaftPeerRole.LEADER) {
        role.shutdownLeaderState(false);
      } else if (old == RaftPeerRole.CANDIDATE) {
        role.shutdownLeaderElection();
      } else if (old == RaftPeerRole.FOLLOWER) {
        role.shutdownFollowerState();
      }
      role.startFollowerState(this);
    }
    return metadataUpdated;
  }

  synchronized void changeToFollowerAndPersistMetadata(long newTerm, Object reason) throws IOException {
    if (changeToFollower(newTerm, false, reason)) {
      state.persistMetadata();
    }
  }

  synchronized void changeToLeader() {
    Preconditions.assertTrue(isCandidate());
    role.shutdownLeaderElection();
    setRole(RaftPeerRole.LEADER, "changeToLeader");
    state.becomeLeader();

    // start sending AppendEntries RPC to followers
    final LogEntryProto e = role.startLeaderState(this, getProxy().getProperties());
    getState().setRaftConf(e);
  }

  Collection<CommitInfoProto> getCommitInfos() {
    final List<CommitInfoProto> infos = new ArrayList<>();
    // add the commit info of this server
    infos.add(commitInfoCache.update(getPeer(), state.getLog().getLastCommittedIndex()));

    // add the commit infos of other servers
    if (isLeader()) {
      role.getLeaderState().ifPresent(
          leader -> leader.updateFollowerCommitInfos(commitInfoCache, infos));
    } else {
      getRaftConf().getPeers().stream()
          .filter(p -> !p.getId().equals(state.getSelfId()))
          .map(RaftPeer::getId)
          .map(commitInfoCache::get)
          .filter(i -> i != null)
          .forEach(infos::add);
    }
    return infos;
  }

  GroupInfoReply getGroupInfo(GroupInfoRequest request) {
    return new GroupInfoReply(request, getRoleInfoProto(),
        state.getStorage().getStorageDir().hasMetaFile(), getCommitInfos(), getGroup());
  }

  public RoleInfoProto getRoleInfoProto() {
    RaftPeerRole currentRole = role.getCurrentRole();
    RoleInfoProto.Builder roleInfo = RoleInfoProto.newBuilder()
        .setSelf(getPeer().getRaftPeerProto())
        .setRole(currentRole)
        .setRoleElapsedTimeMs(role.getRoleElapsedTimeMs());
    switch (currentRole) {
    case CANDIDATE:
      CandidateInfoProto.Builder candidate = CandidateInfoProto.newBuilder()
          .setLastLeaderElapsedTimeMs(state.getLastLeaderElapsedTimeMs());
      roleInfo.setCandidateInfo(candidate);
      break;

    case FOLLOWER:
      role.getFollowerState().ifPresent(fs -> {
        final ServerRpcProto leaderInfo = ServerProtoUtils.toServerRpcProto(
            getRaftConf().getPeer(state.getLeaderId()), fs.getLastRpcTime().elapsedTimeMs());
        roleInfo.setFollowerInfo(FollowerInfoProto.newBuilder()
            .setLeaderInfo(leaderInfo)
            .setOutstandingOp(fs.getOutstandingOp()));
      });
      break;

    case LEADER:
      role.getLeaderState().ifPresent(ls -> {
        final LeaderInfoProto.Builder leader = LeaderInfoProto.newBuilder();
        ls.getLogAppenders().map(LogAppender::getFollower).forEach(f ->
            leader.addFollowerInfo(ServerProtoUtils.toServerRpcProto(
                f.getPeer(), f.getLastRpcResponseTime().elapsedTimeMs())));
        roleInfo.setLeaderInfo(leader);
      });
      break;

    default:
      throw new IllegalStateException("incorrect role of server " + currentRole);
    }
    return roleInfo.build();
  }

  synchronized void changeToCandidate() {
    Preconditions.assertTrue(isFollower());
    role.shutdownFollowerState();
    setRole(RaftPeerRole.CANDIDATE, "changeToCandidate");
    if (state.checkForExtendedNoLeader()) {
      stateMachine.notifyExtendedNoLeader(getGroup(), getRoleInfoProto());
    }
    // start election
    role.startLeaderElection(this);
  }

  @Override
  public String toString() {
    return String.format("%8s ", role) + groupId + " " + state
        + " " + lifeCycle.getCurrentState();
  }

  /**
   * @return null if the server is in leader state.
   */
  private CompletableFuture<RaftClientReply> checkLeaderState(
      RaftClientRequest request, RetryCache.CacheEntry entry) {
    try {
      assertGroup(request.getRequestorId(), request.getRaftGroupId());
    } catch (GroupMismatchException e) {
      return RetryCache.failWithException(e, entry);
    }

    if (!isLeader()) {
      NotLeaderException exception = generateNotLeaderException();
      final RaftClientReply reply = new RaftClientReply(request, exception, getCommitInfos());
      return RetryCache.failWithReply(reply, entry);
    }
    final LeaderState leaderState = role.getLeaderState().orElse(null);
    if (leaderState == null || !leaderState.isReady()) {
      RetryCache.CacheEntry cacheEntry = retryCache.get(request.getClientId(), request.getCallId());
      if (cacheEntry != null && cacheEntry.isCompletedNormally()) {
        return cacheEntry.getReplyFuture();
      }
      return RetryCache.failWithException(new LeaderNotReadyException(getId()), entry);
    }
    return null;
  }

  NotLeaderException generateNotLeaderException() {
    if (lifeCycle.getCurrentState() != RUNNING) {
      return new NotLeaderException(getId(), null, null);
    }
    RaftPeerId leaderId = state.getLeaderId();
    if (leaderId == null || leaderId.equals(state.getSelfId())) {
      // No idea about who is the current leader. Or the peer is the current
      // leader, but it is about to step down
      RaftPeer suggestedLeader = getRaftConf().getRandomPeer(state.getSelfId());
      leaderId = suggestedLeader == null ? null : suggestedLeader.getId();
    }
    RaftConfiguration conf = getRaftConf();
    Collection<RaftPeer> peers = conf.getPeers();
    return new NotLeaderException(getId(), conf.getPeer(leaderId),
        peers.toArray(new RaftPeer[peers.size()]));
  }

  private LifeCycle.State assertLifeCycleState(LifeCycle.State... expected) throws ServerNotReadyException {
    return lifeCycle.assertCurrentState((n, c) -> new ServerNotReadyException("Server " + n
        + " is not " + Arrays.toString(expected) + ": current state is " + c),
        expected);
  }

  void assertGroup(Object requestorId, RaftGroupId requestorGroupId) throws GroupMismatchException {
    if (!groupId.equals(requestorGroupId)) {
      throw new GroupMismatchException(getId()
          + ": The group (" + requestorGroupId + ") of " + requestorId
          + " does not match the group (" + groupId + ") of the server " + getId());
    }
  }

  /**
   * Handle a normal update request from client.
   */
  private CompletableFuture<RaftClientReply> appendTransaction(
      RaftClientRequest request, TransactionContext context,
      RetryCache.CacheEntry cacheEntry) throws IOException {
    assertLifeCycleState(RUNNING);
    CompletableFuture<RaftClientReply> reply;

    final PendingRequest pending;
    synchronized (this) {
      reply = checkLeaderState(request, cacheEntry);
      if (reply != null) {
        return reply;
      }

      // append the message to its local log
      final LeaderState leaderState = role.getLeaderStateNonNull();
      try {
        state.appendLog(context);
      } catch (StateMachineException e) {
        // the StateMachineException is thrown by the SM in the preAppend stage.
        // Return the exception in a RaftClientReply.
        RaftClientReply exceptionReply = new RaftClientReply(request, e, getCommitInfos());
        cacheEntry.failWithReply(exceptionReply);
        // leader will step down here
        if (isLeader() && leaderState != null) {
          leaderState.submitStepDownEvent();
        }
        return CompletableFuture.completedFuture(exceptionReply);
      }

      // put the request into the pending queue
      pending = leaderState.addPendingRequest(request, context);
      leaderState.notifySenders();
    }
    return pending.getFuture();
  }

  @Override
  public CompletableFuture<RaftClientReply> submitClientRequestAsync(
      RaftClientRequest request) throws IOException {
    assertLifeCycleState(RUNNING);
    LOG.debug("{}: receive client request({})", getId(), request);
    if (request.is(RaftClientRequestProto.TypeCase.STALEREAD)) {
      return staleReadAsync(request);
    }

    // first check the server's leader state
    CompletableFuture<RaftClientReply> reply = checkLeaderState(request, null);
    if (reply != null) {
      return reply;
    }

    // let the state machine handle read-only request from client
    final StateMachine stateMachine = getStateMachine();
    if (request.is(RaftClientRequestProto.TypeCase.READ)) {
      // TODO: We might not be the leader anymore by the time this completes.
      // See the RAFT paper section 8 (last part)
      return processQueryFuture(stateMachine.query(request.getMessage()), request);
    }

    if (request.is(RaftClientRequestProto.TypeCase.WATCH)) {
      return watchAsync(request);
    }

    // query the retry cache
    RetryCache.CacheQueryResult previousResult = retryCache.queryCache(
        request.getClientId(), request.getCallId());
    if (previousResult.isRetry()) {
      // if the previous attempt is still pending or it succeeded, return its
      // future
      return previousResult.getEntry().getReplyFuture();
    }
    final RetryCache.CacheEntry cacheEntry = previousResult.getEntry();

    // TODO: this client request will not be added to pending requests until
    // later which means that any failure in between will leave partial state in
    // the state machine. We should call cancelTransaction() for failed requests
    TransactionContext context = stateMachine.startTransaction(request);
    if (context.getException() != null) {
      RaftClientReply exceptionReply = new RaftClientReply(request,
          new StateMachineException(getId(), context.getException()), getCommitInfos());
      cacheEntry.failWithReply(exceptionReply);
      return CompletableFuture.completedFuture(exceptionReply);
    }
    return appendTransaction(request, context, cacheEntry);
  }

  private CompletableFuture<RaftClientReply> watchAsync(RaftClientRequest request) {
    return role.getLeaderState()
        .map(ls -> ls.addWatchReqeust(request))
        .orElseGet(() -> CompletableFuture.completedFuture(
            new RaftClientReply(request, generateNotLeaderException(), getCommitInfos())));
  }

  private CompletableFuture<RaftClientReply> staleReadAsync(RaftClientRequest request) {
    final long minIndex = request.getType().getStaleRead().getMinIndex();
    final long commitIndex = state.getLog().getLastCommittedIndex();
    LOG.debug("{}: minIndex={}, commitIndex={}", getId(), minIndex, commitIndex);
    if (commitIndex < minIndex) {
      final StaleReadException e = new StaleReadException(
          "Unable to serve stale-read due to server commit index = " + commitIndex + " < min = " + minIndex);
      return CompletableFuture.completedFuture(
          new RaftClientReply(request, new StateMachineException(getId(), e), getCommitInfos()));
    }
    return processQueryFuture(getStateMachine().queryStale(request.getMessage(), minIndex), request);
  }

  CompletableFuture<RaftClientReply> processQueryFuture(
      CompletableFuture<Message> queryFuture, RaftClientRequest request) {
    return queryFuture.thenApply(r -> new RaftClientReply(request, r, getCommitInfos()))
        .exceptionally(e -> {
          e = JavaUtils.unwrapCompletionException(e);
          if (e instanceof StateMachineException) {
            return new RaftClientReply(request, (StateMachineException)e, getCommitInfos());
          }
          throw new CompletionException(e);
        });
  }

  @Override
  public RaftClientReply submitClientRequest(RaftClientRequest request)
      throws IOException {
    return waitForReply(getId(), request, submitClientRequestAsync(request));
  }

  RaftClientReply waitForReply(RaftPeerId id,
      RaftClientRequest request, CompletableFuture<RaftClientReply> future)
      throws IOException {
    return waitForReply(id, request, future, e -> new RaftClientReply(request, e, getCommitInfos()));
  }

  static <REPLY extends RaftClientReply> REPLY waitForReply(
      RaftPeerId id, RaftClientRequest request, CompletableFuture<REPLY> future,
      Function<RaftException, REPLY> exceptionReply)
      throws IOException {
    try {
      return future.get();
    } catch (InterruptedException e) {
      final String s = id + ": Interrupted when waiting for reply, request=" + request;
      LOG.info(s, e);
      throw IOUtils.toInterruptedIOException(s, e);
    } catch (ExecutionException e) {
      final Throwable cause = e.getCause();
      if (cause == null) {
        throw new IOException(e);
      }
      if (cause instanceof NotLeaderException ||
          cause instanceof StateMachineException) {
        final REPLY reply = exceptionReply.apply((RaftException) cause);
        if (reply != null) {
          return reply;
        }
      }
      throw IOUtils.asIOException(cause);
    }
  }

  @Override
  public RaftClientReply setConfiguration(SetConfigurationRequest request)
      throws IOException {
    return waitForReply(getId(), request, setConfigurationAsync(request));
  }

  /**
   * Handle a raft configuration change request from client.
   */
  @Override
  public CompletableFuture<RaftClientReply> setConfigurationAsync(
      SetConfigurationRequest request) throws IOException {
    LOG.debug("{}: receive setConfiguration({})", getId(), request);
    assertLifeCycleState(RUNNING);
    assertGroup(request.getRequestorId(), request.getRaftGroupId());

    CompletableFuture<RaftClientReply> reply = checkLeaderState(request, null);
    if (reply != null) {
      return reply;
    }

    final RaftPeer[] peersInNewConf = request.getPeersInNewConf();
    final PendingRequest pending;
    synchronized (this) {
      reply = checkLeaderState(request, null);
      if (reply != null) {
        return reply;
      }

      final RaftConfiguration current = getRaftConf();
      final LeaderState leaderState = role.getLeaderStateNonNull();
      // make sure there is no other raft reconfiguration in progress
      if (!current.isStable() || leaderState.inStagingState() || !state.isConfCommitted()) {
        throw new ReconfigurationInProgressException(
            "Reconfiguration is already in progress: " + current);
      }

      // return success with a null message if the new conf is the same as the current
      if (current.hasNoChange(peersInNewConf)) {
        pending = new PendingRequest(request);
        pending.setReply(new RaftClientReply(request, getCommitInfos()));
        return pending.getFuture();
      }

      // add new peers into the rpc service
      getServerRpc().addPeers(Arrays.asList(peersInNewConf));
      // add staging state into the leaderState
      pending = leaderState.startSetConfiguration(request);
    }
    return pending.getFuture();
  }

  private boolean shouldWithholdVotes(long candidateTerm) {
    if (state.getCurrentTerm() < candidateTerm) {
      return false;
    } else if (isLeader()) {
      return true;
    } else {
      // following a leader and not yet timeout
      return isFollower() && state.hasLeader()
          && role.getFollowerState().map(FollowerState::shouldWithholdVotes).orElse(false);
    }
  }

  /**
   * check if the remote peer is not included in the current conf
   * and should shutdown. should shutdown if all the following stands:
   * 1. this is a leader
   * 2. current conf is stable and has been committed
   * 3. candidate id is not included in conf
   * 4. candidate's last entry's index < conf's index
   */
  private boolean shouldSendShutdown(RaftPeerId candidateId,
      TermIndex candidateLastEntry) {
    return isLeader()
        && getRaftConf().isStable()
        && getState().isConfCommitted()
        && !getRaftConf().containsInConf(candidateId)
        && candidateLastEntry.getIndex() < getRaftConf().getLogEntryIndex()
        && role.getLeaderState().map(ls -> !ls.isBootStrappingPeer(candidateId)).orElse(false);
  }

  @Override
  public RequestVoteReplyProto requestVote(RequestVoteRequestProto r)
      throws IOException {
    final RaftRpcRequestProto request = r.getServerRequest();
    return requestVote(RaftPeerId.valueOf(request.getRequestorId()),
        ProtoUtils.toRaftGroupId(request.getRaftGroupId()),
        r.getCandidateTerm(),
        ServerProtoUtils.toTermIndex(r.getCandidateLastEntry()));
  }

  private RequestVoteReplyProto requestVote(
      RaftPeerId candidateId, RaftGroupId candidateGroupId,
      long candidateTerm, TermIndex candidateLastEntry) throws IOException {
    CodeInjectionForTesting.execute(REQUEST_VOTE, getId(),
        candidateId, candidateTerm, candidateLastEntry);
    LOG.debug("{}: receive requestVote({}, {}, {}, {})",
        getId(), candidateId, candidateGroupId, candidateTerm, candidateLastEntry);
    assertLifeCycleState(RUNNING);
    assertGroup(candidateId, candidateGroupId);

    boolean voteGranted = false;
    boolean shouldShutdown = false;
    final RequestVoteReplyProto reply;
    synchronized (this) {
      final FollowerState fs = role.getFollowerState().orElse(null);
      if (shouldWithholdVotes(candidateTerm)) {
        LOG.info("{}-{}: Withhold vote from candidate {} with term {}. State: leader={}, term={}, lastRpcElapsed={}",
            getId(), role, candidateId, candidateTerm, state.getLeaderId(), state.getCurrentTerm(),
            fs != null? fs.getLastRpcTime().elapsedTimeMs() + "ms": null);
      } else if (state.recognizeCandidate(candidateId, candidateTerm)) {
        final boolean termUpdated = changeToFollower(candidateTerm, true, "recognizeCandidate:" + candidateId);
        // see Section 5.4.1 Election restriction
        if (state.isLogUpToDate(candidateLastEntry) && fs != null) {
          fs.updateLastRpcTime(FollowerState.UpdateType.REQUEST_VOTE);
          state.grantVote(candidateId);
          voteGranted = true;
        }
        if (termUpdated || voteGranted) {
          state.persistMetadata(); // sync metafile
        }
      }
      if (!voteGranted && shouldSendShutdown(candidateId, candidateLastEntry)) {
        shouldShutdown = true;
      }
      reply = ServerProtoUtils.toRequestVoteReplyProto(candidateId, getId(),
          groupId, voteGranted, state.getCurrentTerm(), shouldShutdown);
      if (LOG.isDebugEnabled()) {
        LOG.debug("{} replies to vote request: {}. Peer's state: {}",
            getId(), ServerProtoUtils.toString(reply), state);
      }
    }
    return reply;
  }

  private void validateEntries(long expectedTerm, TermIndex previous,
      LogEntryProto... entries) {
    if (entries != null && entries.length > 0) {
      final long index0 = entries[0].getIndex();

      if (previous == null || previous.getTerm() == 0) {
        Preconditions.assertTrue(index0 == 0,
            "Unexpected Index: previous is null but entries[%s].getIndex()=%s",
            0, index0);
      } else {
        Preconditions.assertTrue(previous.getIndex() == index0 - 1,
            "Unexpected Index: previous is %s but entries[%s].getIndex()=%s",
            previous, 0, index0);
      }

      for (int i = 0; i < entries.length; i++) {
        final long t = entries[i].getTerm();
        Preconditions.assertTrue(expectedTerm >= t,
            "Unexpected Term: entries[%s].getTerm()=%s but expectedTerm=%s",
            i, t, expectedTerm);

        final long indexi = entries[i].getIndex();
        Preconditions.assertTrue(indexi == index0 + i,
            "Unexpected Index: entries[%s].getIndex()=%s but entries[0].getIndex()=%s",
            i, indexi, index0);
      }
    }
  }

  @Override
  public AppendEntriesReplyProto appendEntries(AppendEntriesRequestProto r)
      throws IOException {
    try {
      return appendEntriesAsync(r).join();
    } catch (CompletionException e) {
      throw IOUtils.asIOException(JavaUtils.unwrapCompletionException(e));
    }
  }

  @Override
  public CompletableFuture<AppendEntriesReplyProto> appendEntriesAsync(AppendEntriesRequestProto r)
      throws IOException {
    // TODO avoid converting list to array
    final RaftRpcRequestProto request = r.getServerRequest();
    final LogEntryProto[] entries = r.getEntriesList()
        .toArray(new LogEntryProto[r.getEntriesCount()]);
    final TermIndex previous = r.hasPreviousLog() ?
        ServerProtoUtils.toTermIndex(r.getPreviousLog()) : null;
    final RaftPeerId requestorId = RaftPeerId.valueOf(request.getRequestorId());

    preAppendEntriesAsync(requestorId, ProtoUtils.toRaftGroupId(request.getRaftGroupId()), r.getLeaderTerm(),
        previous, r.getLeaderCommit(), r.getInitializing(), entries);
    try {
      return appendEntriesAsync(requestorId, r.getLeaderTerm(), previous, r.getLeaderCommit(),
          request.getCallId(), r.getInitializing(), r.getCommitInfosList(), entries);
    } catch(Throwable t) {
      LOG.error(getId() + ": Failed appendEntriesAsync " + r, t);
      throw t;
    }
  }

  static void logAppendEntries(boolean isHeartbeat, Supplier<String> message) {
    if (isHeartbeat) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("HEARTBEAT: " + message.get());
      }
    } else {
      if (LOG.isDebugEnabled()) {
        LOG.debug(message.get());
      }
    }
  }

  private Optional<FollowerState> updateLastRpcTime(FollowerState.UpdateType updateType) {
    final Optional<FollowerState> fs = role.getFollowerState();
    if (fs.isPresent() && lifeCycle.getCurrentState() == RUNNING) {
      fs.get().updateLastRpcTime(updateType);
      return fs;
    } else {
      return Optional.empty();
    }
  }

  private void preAppendEntriesAsync(RaftPeerId leaderId, RaftGroupId leaderGroupId, long leaderTerm,
      TermIndex previous, long leaderCommit, boolean initializing, LogEntryProto... entries) throws IOException {
    CodeInjectionForTesting.execute(APPEND_ENTRIES, getId(),
        leaderId, leaderTerm, previous, leaderCommit, initializing, entries);

    final LifeCycle.State currentState = assertLifeCycleState(STARTING, RUNNING);
    if (currentState == STARTING) {
      if (role.getCurrentRole() == null) {
        throw new ServerNotReadyException("The role of Server " + getId() + " is not yet initialized.");
      }
    }
    assertGroup(leaderId, leaderGroupId);

    try {
      validateEntries(leaderTerm, previous, entries);
    } catch (IllegalArgumentException e) {
      throw new IOException(e);
    }
  }

  private CompletableFuture<AppendEntriesReplyProto> appendEntriesAsync(
      RaftPeerId leaderId, long leaderTerm, TermIndex previous, long leaderCommit, long callId, boolean initializing,
      List<CommitInfoProto> commitInfos, LogEntryProto... entries) {
    final boolean isHeartbeat = entries.length == 0;
    logAppendEntries(isHeartbeat,
        () -> getId() + ": receive appendEntries(" + leaderId + ", " + leaderTerm + ", "
            + previous + ", " + leaderCommit + ", " + initializing
            + ", commits" + ProtoUtils.toString(commitInfos)
            + ", entries: " + ServerProtoUtils.toString(entries));
    final List<CompletableFuture<Long>> futures;

    final long currentTerm;
    final long followerCommit = state.getLog().getLastCommittedIndex();
    final long nextIndex = state.getNextIndex();
    final Optional<FollowerState> followerState;
    synchronized (this) {
      final boolean recognized = state.recognizeLeader(leaderId, leaderTerm);
      currentTerm = state.getCurrentTerm();
      if (!recognized) {
        final AppendEntriesReplyProto reply = ServerProtoUtils.toAppendEntriesReplyProto(
            leaderId, getId(), groupId, currentTerm, followerCommit, nextIndex, NOT_LEADER, callId);
        if (LOG.isDebugEnabled()) {
          LOG.debug("{}: Not recognize {} (term={}) as leader, state: {} reply: {}",
              getId(), leaderId, leaderTerm, state, ServerProtoUtils.toString(reply));
        }
        return CompletableFuture.completedFuture(reply);
      }
      try {
        changeToFollowerAndPersistMetadata(leaderTerm, "appendEntries");
      } catch (IOException e) {
        return JavaUtils.completeExceptionally(e);
      }
      state.setLeader(leaderId, "appendEntries");

      if (!initializing && lifeCycle.compareAndTransition(STARTING, RUNNING)) {
        role.startFollowerState(this);
      }
      followerState = updateLastRpcTime(FollowerState.UpdateType.APPEND_START);

      // Check that the append entries are not inconsistent. There are 3
      // scenarios which can result in inconsistency:
      //      1. There is a snapshot installation in progress
      //      2. There is an overlap between the snapshot index and the entries
      //      3. There is a gap between the local log and the entries
      // In any of these scenarios, we should retrun an INCONSISTENCY reply
      // back to leader so that the leader can update this follower's next
      // index.

      AppendEntriesReplyProto inconsistencyReply = checkInconsistentAppendEntries(
          leaderId, currentTerm, followerCommit, previous, nextIndex, callId, entries);
      if (inconsistencyReply != null) {
        followerState.ifPresent(fs -> fs.updateLastRpcTime(FollowerState.UpdateType.APPEND_COMPLETE));
        return CompletableFuture.completedFuture(inconsistencyReply);
      }

      state.updateConfiguration(entries);
    }

    futures = state.getLog().append(entries);
    commitInfos.forEach(commitInfoCache::update);

    if (!isHeartbeat) {
      CodeInjectionForTesting.execute(RaftLog.LOG_SYNC, getId(), null);
    }
    return JavaUtils.allOf(futures).whenCompleteAsync(
        (r, t) -> followerState.ifPresent(fs -> fs.updateLastRpcTime(FollowerState.UpdateType.APPEND_COMPLETE))
    ).thenApply(v -> {
      final AppendEntriesReplyProto reply;
      synchronized(this) {
        state.updateStatemachine(leaderCommit, currentTerm);
        final long n = isHeartbeat? state.getLog().getNextIndex(): entries[entries.length - 1].getIndex() + 1;
        reply = ServerProtoUtils.toAppendEntriesReplyProto(leaderId, getId(), groupId, currentTerm,
            state.getLog().getLastCommittedIndex(), n, SUCCESS, callId);
      }
      logAppendEntries(isHeartbeat, () ->
          getId() + ": succeeded to handle AppendEntries. Reply: " + ServerProtoUtils.toString(reply));
      return reply;
    });
  }

  private AppendEntriesReplyProto checkInconsistentAppendEntries(RaftPeerId leaderId, long currentTerm,
      long followerCommit, TermIndex previous, long nextIndex, long callId, LogEntryProto... entries) {
    long replyNextIndex = -1;

    // Check if a snapshot installation through state machine is in progress.
    if (inProgressInstallSnapshotRequest.get() != null) {
      replyNextIndex = Math.min(nextIndex, previous.getIndex());
      if (LOG.isDebugEnabled()) {
        LOG.debug("{}: Cannot append entries as snapshot installation is in " +
            "progress. Follower next index: {}", getId(), replyNextIndex);
      }
    }

    // If a snapshot installation has happened, the new snapshot might
    // include the log entry indices sent as part of the
    // AppendEntriesRequestProto. Check that the first log entry proto is
    // greater than the last index included in the latest snapshot. If not,
    // the leader should be informed about the new snapshot index so that
    // it can send log entries only from the next log index
    long snapshotIndex = state.getSnapshotIndex();
    if (snapshotIndex > 0 && entries != null && entries.length > 0
        && entries[0].getIndex() <= snapshotIndex) {
      replyNextIndex = snapshotIndex + 1;
      if (LOG.isDebugEnabled()) {
        LOG.debug("{}: Cannot append entries as latest snapshot already has " +
            "the append entries. Snapshot index: {}, first append entry " +
            "index: {}.", getId(), snapshotIndex, entries[0].getIndex());
      }
    }

    // We need to check if "previous" is in the local peer. Note that it is
    // possible that "previous" is covered by the latest snapshot: e.g.,
    // it's possible there's no log entries outside of the latest snapshot.
    // However, it is not possible that "previous" index is smaller than the
    // last index included in snapshot. This is because indices <= snapshot's
    // last index should have been committed.
    if (previous != null && !containPrevious(previous)) {
      replyNextIndex = Math.min(nextIndex, previous.getIndex());
      if (LOG.isDebugEnabled()) {
        LOG.debug("{}: Cannot append entries as there is a gap between " +
            "local log and append entries. Previous is not present. " +
            "Previous: {}, follower next index: {}", getId(), previous, replyNextIndex);
      }
    }

    if (replyNextIndex != -1) {
      final AppendEntriesReplyProto reply = ServerProtoUtils.toAppendEntriesReplyProto(
          leaderId, getId(), groupId, currentTerm, followerCommit, replyNextIndex,
          INCONSISTENCY, callId);
      if (LOG.isDebugEnabled()) {
        LOG.debug("{}: inconsistency entries. Reply:{}", getId(), ServerProtoUtils.toString(reply));
      }
      return reply;
    }

    return null;
  }

  private boolean containPrevious(TermIndex previous) {
    if (LOG.isTraceEnabled()) {
      LOG.trace("{}: prev:{}, latestSnapshot:{}, latestInstalledSnapshot:{}",
          getId(), previous, state.getLatestSnapshot(), state.getLatestInstalledSnapshot());
    }
    return state.getLog().contains(previous)
        ||  (state.getLatestSnapshot() != null
             && state.getLatestSnapshot().getTermIndex().equals(previous))
        || (state.getLatestInstalledSnapshot() != null)
             && state.getLatestInstalledSnapshot().equals(previous);
  }

  @Override
  public InstallSnapshotReplyProto installSnapshot(
      InstallSnapshotRequestProto request) throws IOException {
    final RaftRpcRequestProto r = request.getServerRequest();
    final RaftPeerId leaderId = RaftPeerId.valueOf(r.getRequestorId());
    final RaftGroupId leaderGroupId = ProtoUtils.toRaftGroupId(r.getRaftGroupId());
    CodeInjectionForTesting.execute(INSTALL_SNAPSHOT, getId(),
        leaderId, request);
    LOG.debug("{}: receive installSnapshot({})", getId(), request);

    assertLifeCycleState(STARTING, RUNNING);
    assertGroup(leaderId, leaderGroupId);

    // Check if install snapshot from Leader is enabled
    if (installSnapshotEnabled) {
      // Leader has sent InstallSnapshot request with SnapshotInfo. Install the snapshot.
      if (request.hasSnapshotChunk()) {
        return checkAndInstallSnapshot(request, leaderId);
      }
    } else {
      // Leader has only sent a notification to install snapshot. Inform State Machine to install snapshot.
      if (request.hasNotification()) {
        return notifyStateMachineToInstallSnapshot(request, leaderId);
      }
    }
    // There is a mismatch between configurations on leader and follower.
    final InstallSnapshotReplyProto reply = ServerProtoUtils
        .toInstallSnapshotReplyProto(leaderId, getId(), groupId,
            InstallSnapshotResult.CONF_MISMATCH);
    LOG.error("{}: Configuration Mismatch ({}): Leader {} has it set to {} but follower {} has it set to {}",
        getId(), RaftServerConfigKeys.Log.Appender.INSTALL_SNAPSHOT_ENABLED_KEY,
        leaderId, request.hasSnapshotChunk(), getId(), installSnapshotEnabled);
    return reply;
  }

  private InstallSnapshotReplyProto checkAndInstallSnapshot(
      InstallSnapshotRequestProto request, RaftPeerId leaderId) throws IOException {
    final long currentTerm;
    final long leaderTerm = request.getLeaderTerm();
    InstallSnapshotRequestProto.SnapshotChunkProto snapshotChunkRequest = request.getSnapshotChunk();
    final TermIndex lastTermIndex = ServerProtoUtils.toTermIndex(snapshotChunkRequest.getTermIndex());
    final long lastIncludedIndex = lastTermIndex.getIndex();
    final Optional<FollowerState> followerState;
    synchronized (this) {
      final boolean recognized = state.recognizeLeader(leaderId, leaderTerm);
      currentTerm = state.getCurrentTerm();
      if (!recognized) {
        final InstallSnapshotReplyProto reply = ServerProtoUtils
            .toInstallSnapshotReplyProto(leaderId, getId(), groupId, currentTerm,
                snapshotChunkRequest.getRequestIndex(), InstallSnapshotResult.NOT_LEADER);
        LOG.debug("{}: do not recognize leader for installing snapshot." +
            " Reply: {}", getId(), reply);
        return reply;
      }
      changeToFollowerAndPersistMetadata(leaderTerm, "installSnapshot");
      state.setLeader(leaderId, "installSnapshot");

      followerState = updateLastRpcTime(FollowerState.UpdateType.INSTALL_SNAPSHOT_START);
      try {
        // Check and append the snapshot chunk. We simply put this in lock
        // considering a follower peer requiring a snapshot installation does not
        // have a lot of requests
        Preconditions.assertTrue(
            state.getLog().getNextIndex() <= lastIncludedIndex,
            "%s log's next id is %s, last included index in snapshot is %s",
            getId(), state.getLog().getNextIndex(), lastIncludedIndex);

        //TODO: We should only update State with installed snapshot once the request is done.
        state.installSnapshot(request);

        // update the committed index
        // re-load the state machine if this is the last chunk
        if (snapshotChunkRequest.getDone()) {
          state.reloadStateMachine(lastIncludedIndex, leaderTerm);
        }
      } finally {
        updateLastRpcTime(FollowerState.UpdateType.INSTALL_SNAPSHOT_COMPLETE);
      }
    }
    if (snapshotChunkRequest.getDone()) {
      LOG.info("{}:{} successfully install the whole snapshot-{}", getId(), getGroupId(),
          lastIncludedIndex);
    }
    return ServerProtoUtils.toInstallSnapshotReplyProto(leaderId, getId(), groupId,
        currentTerm, snapshotChunkRequest.getRequestIndex(), InstallSnapshotResult.SUCCESS);
  }

  private InstallSnapshotReplyProto notifyStateMachineToInstallSnapshot(
      InstallSnapshotRequestProto request, RaftPeerId leaderId) throws IOException {
    final long currentTerm;
    final long leaderTerm = request.getLeaderTerm();
    final TermIndex firstAvailableLogTermIndex = ServerProtoUtils.toTermIndex(
        request.getNotification().getFirstAvailableTermIndex());
    final long firstAvailableLogIndex = firstAvailableLogTermIndex.getIndex();

    synchronized (this) {
      final boolean recognized = state.recognizeLeader(leaderId, leaderTerm);
      currentTerm = state.getCurrentTerm();
      if (!recognized) {
        final InstallSnapshotReplyProto reply = ServerProtoUtils
            .toInstallSnapshotReplyProto(leaderId, getId(), groupId, currentTerm,
                InstallSnapshotResult.NOT_LEADER, -1);
        LOG.debug("{}: do not recognize leader for installing snapshot." +
            " Reply: {}", getId(), reply);
        return reply;
      }
      changeToFollowerAndPersistMetadata(leaderTerm, "installSnapshot");
      state.setLeader(leaderId, "installSnapshot");

      updateLastRpcTime(FollowerState.UpdateType.INSTALL_SNAPSHOT_NOTIFICATION);

      if (inProgressInstallSnapshotRequest.compareAndSet(null, firstAvailableLogTermIndex)) {

        // Check if snapshot index is already at par or ahead of the first
        // available log index of the Leader.
        long snapshotIndex = state.getSnapshotIndex();
        if (snapshotIndex + 1 >= firstAvailableLogIndex) {
          // State Machine has already installed the snapshot. Return the
          // latest snapshot index to the Leader.

          inProgressInstallSnapshotRequest.compareAndSet(firstAvailableLogTermIndex, null);
          final InstallSnapshotReplyProto reply = ServerProtoUtils.toInstallSnapshotReplyProto(
              leaderId, getId(), groupId, currentTerm,
              InstallSnapshotResult.ALREADY_INSTALLED, snapshotIndex);
          LOG.info("{}: StateMachine latest installed snapshot index: {}. Reply: {}",
              getId(), snapshotIndex, reply);

          return reply;
        }

        // This is the first installSnapshot notify request for this term and
        // index. Notify the state machine to install the snapshot.
        LOG.debug("{}: notifying state machine to install snapshot. Next log " +
                "index is {} but the leader's first available log index is {}.",
            getId(), state.getLog().getNextIndex(), firstAvailableLogIndex);

        stateMachine.notifyInstallSnapshotFromLeader(firstAvailableLogTermIndex)
            .whenComplete((reply, exception) -> {
              if (exception != null) {
                LOG.error(getId() + ": State Machine failed to install snapshot", exception);
                inProgressInstallSnapshotRequest.compareAndSet(firstAvailableLogTermIndex, null);
                return;
              }

              if (reply != null) {
                stateMachine.pause();
                state.reloadStateMachine(reply.getIndex(), leaderTerm);
                state.updateInstalledSnapshotIndex(reply);
              }
              inProgressInstallSnapshotRequest.compareAndSet(firstAvailableLogTermIndex, null);
              return;
            });

        LOG.info("{}: StateMachine notified to install snapshot, Request: {}");
        return ServerProtoUtils.toInstallSnapshotReplyProto(leaderId, getId(), groupId,
            currentTerm, InstallSnapshotResult.SUCCESS, -1);
      }

      LOG.debug("{}: StateMachine snapshot installation is in progress. " +
              "InProgress Request: {}", getId(), inProgressInstallSnapshotRequest.get());
      return ServerProtoUtils.toInstallSnapshotReplyProto(leaderId, getId(), groupId,
          currentTerm, InstallSnapshotResult.IN_PROGRESS, -1);
    }
  }

  synchronized InstallSnapshotRequestProto createInstallSnapshotRequest(
      RaftPeerId targetId, String requestId, int requestIndex,
      SnapshotInfo snapshot, List<FileChunkProto> chunks, boolean done) {
    OptionalLong totalSize = snapshot.getFiles().stream()
        .mapToLong(FileInfo::getFileSize).reduce(Long::sum);
    assert totalSize.isPresent();
    return ServerProtoUtils.toInstallSnapshotRequestProto(getId(), targetId, groupId,
        requestId, requestIndex, state.getCurrentTerm(), snapshot.getTermIndex(),
        chunks, totalSize.getAsLong(), done);
  }

  synchronized InstallSnapshotRequestProto createInstallSnapshotRequest(
      RaftPeerId targetId, TermIndex firstAvailableLogTermIndex) {

    assert (firstAvailableLogTermIndex.getIndex() > 0);
    return ServerProtoUtils.toInstallSnapshotRequestProto(getId(),
        targetId, groupId, state.getCurrentTerm(), firstAvailableLogTermIndex);
  }

  synchronized RequestVoteRequestProto createRequestVoteRequest(
      RaftPeerId targetId, long term, TermIndex lastEntry) {
    return ServerProtoUtils.toRequestVoteRequestProto(getId(), targetId,
        groupId, term, lastEntry);
  }

  public void submitUpdateCommitEvent() {
    role.getLeaderState().ifPresent(LeaderState::submitUpdateCommitEvent);
  }

  /**
   * The log has been submitted to the state machine. Use the future to update
   * the pending requests and retry cache.
   * @param logEntry the log entry that has been submitted to the state machine
   * @param stateMachineFuture the future returned by the state machine
   *                           from which we will get transaction result later
   */
  private CompletableFuture<Message> replyPendingRequest(
      LogEntryProto logEntry, CompletableFuture<Message> stateMachineFuture) {
    Preconditions.assertTrue(logEntry.hasStateMachineLogEntry());
    final StateMachineLogEntryProto smLog = logEntry.getStateMachineLogEntry();
    // update the retry cache
    final ClientId clientId = ClientId.valueOf(smLog.getClientId());
    final long callId = smLog.getCallId();
    final RaftPeerId serverId = getId();
    final RetryCache.CacheEntry cacheEntry = retryCache.getOrCreateEntry(clientId, callId);
    if (cacheEntry.isFailed()) {
      retryCache.refreshEntry(new RetryCache.CacheEntry(cacheEntry.getKey()));
    }

    final long logIndex = logEntry.getIndex();
    return stateMachineFuture.whenComplete((reply, exception) -> {
      final RaftClientReply r;
      if (exception == null) {
        r = new RaftClientReply(clientId, serverId, groupId, callId, true, reply, null, logIndex, getCommitInfos());
      } else {
        // the exception is coming from the state machine. wrap it into the
        // reply as a StateMachineException
        final StateMachineException e = new StateMachineException(getId(), exception);
        r = new RaftClientReply(clientId, serverId, groupId, callId, false, null, e, logIndex, getCommitInfos());
      }

      // update pending request
      synchronized (RaftServerImpl.this) {
        final LeaderState leaderState = role.getLeaderState().orElse(null);
        if (isLeader() && leaderState != null) { // is leader and is running
          leaderState.replyPendingRequest(logIndex, r);
        }
      }
      cacheEntry.updateResult(r);
    });
  }

  public long[] getFollowerNextIndices() {
    if (!isLeader()) {
      return null;
    }
    return role.getLeaderState().map(LeaderState::getFollowerNextIndices).orElse(null);
  }

  CompletableFuture<Message> applyLogToStateMachine(LogEntryProto next) {
    final StateMachine stateMachine = getStateMachine();
    if (!next.hasStateMachineLogEntry()) {
      stateMachine.notifyIndexUpdate(next.getTerm(), next.getIndex());
    }
    if (next.hasConfigurationEntry()) {
      // the reply should have already been set. only need to record
      // the new conf in the metadata file.
      state.writeRaftConfiguration(next);
    } else if (next.hasStateMachineLogEntry()) {
      // check whether there is a TransactionContext because we are the leader.
      TransactionContext trx = role.getLeaderState()
          .map(leader -> leader.getTransactionContext(next.getIndex())).orElseGet(
              () -> TransactionContext.newBuilder()
                  .setServerRole(role.getCurrentRole())
                  .setStateMachine(stateMachine)
                  .setLogEntry(next)
                  .build());

      // Let the StateMachine inject logic for committed transactions in sequential order.
      trx = stateMachine.applyTransactionSerial(trx);

      try {
        // TODO: This step can be parallelized
        CompletableFuture<Message> stateMachineFuture =
            stateMachine.applyTransaction(trx);
        return replyPendingRequest(next, stateMachineFuture);
      } catch (Throwable e) {
        LOG.error("{}: applyTransaction failed for index:{} proto:{}", getId(),
            next.getIndex(), ServerProtoUtils.toString(next), e.getMessage());
        throw e;
      }
    }
    return null;
  }

  public void failClientRequest(LogEntryProto logEntry) {
    if (logEntry.hasStateMachineLogEntry()) {
      final StateMachineLogEntryProto smLog = logEntry.getStateMachineLogEntry();
      final ClientId clientId = ClientId.valueOf(smLog.getClientId());
      final long callId = smLog.getCallId();
      final RetryCache.CacheEntry cacheEntry = getRetryCache().get(clientId, callId);
      if (cacheEntry != null) {
        final RaftClientReply reply = new RaftClientReply(clientId, getId(), getGroupId(),
            callId, false, null, generateNotLeaderException(),
            logEntry.getIndex(), getCommitInfos());
        cacheEntry.failWithReply(reply);
      }
    }
  }

  private class RaftServerJmxAdapter extends JmxRegister implements RaftServerMXBean {
    @Override
    public String getId() {
      return getState().getSelfId().toString();
    }

    @Override
    public String getLeaderId() {
      return getState().getLeaderId().toString();
    }

    @Override
    public long getCurrentTerm() {
      return getState().getCurrentTerm();
    }

    @Override
    public String getGroupId() {
      return RaftServerImpl.this.getGroupId().toString();
    }

    @Override
    public String getRole() {
      return role.toString();
    }

    @Override
    public List<String> getFollowers() {
      return role.getLeaderState().map(LeaderState::getFollowers).orElse(Collections.emptyList())
          .stream().map(RaftPeer::toString).collect(Collectors.toList());
    }
  }
}
