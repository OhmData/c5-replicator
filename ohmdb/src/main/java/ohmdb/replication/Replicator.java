package ohmdb.replication;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import ohmdb.replication.rpc.RpcReply;
import ohmdb.replication.rpc.RpcRequest;
import ohmdb.replication.rpc.RpcWireReply;
import ohmdb.replication.rpc.RpcWireRequest;
import org.jetlang.channels.AsyncRequest;
import org.jetlang.channels.MemoryRequestChannel;
import org.jetlang.channels.Request;
import org.jetlang.channels.RequestChannel;
import org.jetlang.core.Callback;
import org.jetlang.core.Disposable;
import org.jetlang.fibers.Fiber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static ohmdb.replication.Raft.LogEntry;

/**
 * Single instantation of a raft / log / lease
 */
public class Replicator {
    private static final Logger LOG = LoggerFactory.getLogger(Replicator.class);

    private final RequestChannel<RpcRequest, RpcWireReply> sendRpcChannel;
    private final RequestChannel<RpcWireRequest, RpcReply> incomingChannel = new MemoryRequestChannel<>();

    private final Fiber fiber;
    private final long myId;
    private final String quorumId;

    private final ImmutableList<Long> peers;
    private  ArrayList<Long> peerNextIndex;


    private static class IntLogRequest {
        public final byte[] datum;
        public final SettableFuture<Long> logNumberNotifation;
        public final SettableFuture<Object> durableNofication;
        public final SettableFuture<Object> localLogNotification;

        private IntLogRequest(byte[] datum) {
            this.datum = datum;
            this.logNumberNotifation = SettableFuture.create();
            this.durableNofication = SettableFuture.create();
            this.localLogNotification = SettableFuture.create();
        }
    }
    private final BlockingQueue<IntLogRequest> logRequests = new ArrayBlockingQueue<>(100);


    // What state is this instance in?
    public enum State {
        INIT,
        FOLLOWER,
        CANDIDATE,
        LEADER,
    }

    // Initial state == CANDIDATE
    State myState = State.FOLLOWER;

    // In theory these are persistent:
    long currentTerm;
    long votedFor;

    // Election timers, etc.
    private long lastRPC = 0;
    private long myElectionTimeout;
    private Disposable electionChecker;


    private final RaftLogAbstraction log;
    final RaftInformationInterface info;
    final RaftInfoPersistence persister;


    public Replicator(Fiber fiber,
                      long myId,
                      String quorumId,
                      List<Long> peers,
                      RaftLogAbstraction log,
                      RaftInformationInterface info,
                      RaftInfoPersistence persister,
                      RequestChannel<RpcRequest, RpcWireReply> sendRpcChannel) {
        this.fiber = fiber;
        this.myId = myId;
        this.quorumId = quorumId;
        this.peers = ImmutableList.copyOf(peers);
        this.sendRpcChannel = sendRpcChannel;
        this.log = log;
        this.info = info;
        this.persister = persister;
        Random r = new Random();
        this.myElectionTimeout = r.nextInt((int) info.electionTimeout()) + info.electionTimeout();

        assert this.peers.contains(this.myId);

        fiber.execute(new Runnable() {
            @Override
            public void run() {
                readPersistentData();
            }
        });

        incomingChannel.subscribe(fiber, new Callback<Request<RpcWireRequest, RpcReply>>() {
            @Override
            public void onMessage(Request<RpcWireRequest, RpcReply> message) {
                onIncomingMessage(message);
            }
        });

        electionChecker = fiber.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                checkOnElection();
            }
        }, info.electionCheckRate(), info.electionCheckRate(), TimeUnit.MILLISECONDS);

        fiber.start();
    }

    // public API:

    /**
     * Log a datum
     * @param datum some data to log.
     * @return a listenable for the index number OR null if we aren't the leader.
     */
    public ListenableFuture<Long> logData(final byte[] datum) throws InterruptedException {
        if (!isLeader()) {
            return null;
        }

        IntLogRequest req = new IntLogRequest(datum);
        logRequests.put(req);

        // TODO return the durable notification future?
        return req.logNumberNotifation;
    }

    @FiberOnly
    private void readPersistentData() {
        currentTerm = persister.readCurrentTerm();
        votedFor = persister.readVotedFor();
    }

    @FiberOnly
    private void onIncomingMessage(Request<RpcWireRequest, RpcReply> message) {
        RpcWireRequest req = message.getRequest();
        if (req.isRequestVoteMessage()) {
            doRequestVote(message);

        } else if (req.isAppendMessage()) {
            doAppendMessage(message);


        } else {
            LOG.warn("{} Got a message of protobuf type I dont know: {}", myId, req);
        }

    }

    @FiberOnly
    private void doRequestVote(Request<RpcWireRequest, RpcReply> message) {
        Raft.RequestVote msg = message.getRequest().getRequestVoteMessage();

        // 1. Return if term < currentTerm (sec 5.1)
        if (msg.getTerm() < currentTerm) {
            Raft.RequestVoteReply m = Raft.RequestVoteReply.newBuilder()
                    .setQuorumId(quorumId)
                    .setTerm(currentTerm)
                    .setVoteGranted(false)
                    .build();
            RpcReply reply = new RpcReply(message.getRequest(), m);
            message.reply(reply);
            return;
        }

        // 2. if term > currentTerm, currentTerm <- term
        if (msg.getTerm() > currentTerm) {
            LOG.debug("{} requestVote rpc, pushing forward currentTerm {} to {}", currentTerm, msg.getTerm());
            setCurrentTerm(msg.getTerm());

            // 2a. Step down if candidate or leader.
            if (myState != State.FOLLOWER) {
                LOG.debug("{} stepping down to follower, currentTerm: {}", myId, currentTerm);

                haltLeader();
            }
        }

        // 3. if votedFor is null (0), or candidateId, and candidate's log
        // is at least as complete as local log (sec 5.2, 5.4), grant vote
        // and reset election timeout.

        boolean vote = false;
        if ( (log.getLastTerm() <= msg.getLastLogTerm())
                &&
                log.getLastIndex() <= msg.getLastLogIndex()) {
            // we can vote for this because the candidate's log is at least as
            // complete as the local log.

            if (votedFor == 0 || votedFor == message.getRequest().from) {
                setVotedFor(message.getRequest().from);
                lastRPC = info.currentTimeMillis();
                vote = true;
            }
        }

        LOG.debug("{} sending vote reply to {} vote = {}, voted = {}", myId, message.getRequest().from, votedFor, vote);
        Raft.RequestVoteReply m = Raft.RequestVoteReply.newBuilder()
                .setQuorumId(quorumId)
                .setTerm(currentTerm)
                .setVoteGranted(vote)
                .build();
        RpcReply reply = new RpcReply(message.getRequest(), m);
        message.reply(reply);
    }

    @FiberOnly
    private void doAppendMessage(Request<RpcWireRequest, RpcReply> message) {
        Raft.AppendEntries msg = message.getRequest().getAppendMessage();

        // 1. return if term < currentTerm (sec 5.1)
        if (msg.getTerm() < currentTerm) {
            // TODO is this the correct message reply?
            Raft.AppendEntriesReply m = Raft.AppendEntriesReply.newBuilder()
                    .setQuorumId(quorumId)
                    .setTerm(currentTerm)
                    .setSuccess(false)
                    .build();

            RpcReply reply = new RpcReply(message.getRequest(), m);
            message.reply(reply);
            return;
        }

        // 2. if term > currentTerm, set it (sec 5.1)
        if (msg.getTerm() > currentTerm) {
            setCurrentTerm(msg.getTerm());
        }

        // 3. Step down if we are a leader or a candidate (sec 5.2, 5.5)
        if (myState != State.FOLLOWER) {
            haltLeader();
        }

        // 4. reset election timeout
        lastRPC = info.currentTimeMillis();

        if (msg.getEntriesCount() == 0) {
            Raft.AppendEntriesReply m = Raft.AppendEntriesReply.newBuilder()
                    .setQuorumId(quorumId)
                    .setTerm(currentTerm)
                    .setSuccess(true)
                    .build();

            RpcReply reply = new RpcReply(message.getRequest(), m);
            message.reply(reply);
            return;
        }

        // 5. return failure if log doesn't contain an entry at
        // prevLogIndex who's term matches prevLogTerm (sec 5.3)
        long msgPrevLogIndex = msg.getPrevLogIndex();
        long msgPrevLogTerm = msg.getPrevLogTerm();
        if (log.getLogTerm(msgPrevLogIndex) != msgPrevLogTerm) {
            Raft.AppendEntriesReply m = Raft.AppendEntriesReply.newBuilder()
                    .setQuorumId(quorumId)
                    .setTerm(currentTerm)
                    .setSuccess(false)
                    .build();

            RpcReply reply = new RpcReply(message.getRequest(), m);
            message.reply(reply);
            return;
        }

        // 6. if existing entries conflict with new entries, delete all
        // existing entries starting with first conflicting entry (sec 5.3)
        // 7. Append any new entries not already in the log.

        // TODO consider how the async nature of logging changes this. Since we also want to probably
        // not allow another append entries to be processed, that may change things.
        List<LogEntry> entries =  msg.getEntriesList();
        for (LogEntry entry : entries) {
            long entryIndex = entry.getIndex();

            if (entryIndex == (log.getLastIndex()+1)) {
                // the very next index.
                LOG.debug("{} new log entry for idx {} term {}", myId, entryIndex, entry.getTerm());

                SettableFuture<Object> logCommitNotification = SettableFuture.create();
                long newEntry = log.logEntry(entry.getData().toByteArray(), entry.getTerm(), logCommitNotification);

                // TODO wait for log commit?
                //logCommitNotification.get();

                assert newEntry == entryIndex;

                continue;
            }

            if (entryIndex > (log.getLastIndex()+1)) {
                // ok this entry is still beyond the LAST entry, so we have a problem:
                LOG.error("{} log entry missing, my last was {} and the next in the message is {}",
                        myId, log.getLastIndex(), entryIndex);

                // TODO handle this situation a little better if possible
                // reply with an error message leaving the log borked.
                Raft.AppendEntriesReply m = Raft.AppendEntriesReply.newBuilder()
                        .setQuorumId(quorumId)
                        .setTerm(currentTerm)
                        .setSuccess(false)
                        .build();

                RpcReply reply = new RpcReply(message.getRequest(), m);
                message.reply(reply);
                return;
            }

            // at this point entryIndex should be <= log.getLastIndex
            if (log.getLogTerm(entryIndex) != entry.getTerm()) {
                // conflict:
                LOG.debug("{} log conflict at idx {} my term: {} term from leader: {}", myId,
                        entryIndex, log.getLogTerm(entryIndex), entry.getTerm());
                // delete this and all subsequent entries:
                ListenableFuture<Boolean> truncateResult = log.truncateLog(entryIndex);
                // TODO figure out how to deal with this.
                try {
                    truncateResult.get();
                } catch (InterruptedException|ExecutionException e) {
                    LOG.error("oops", e);
                }
            }
        }

        // 8. apply newly committed entries to state machine
        long lastCommittedIdx = msg.getCommitIndex();

        // TODO make/broadcast 'lastCommittedIdx' known throughout the local system.

        Raft.AppendEntriesReply m = Raft.AppendEntriesReply.newBuilder()
                .setQuorumId(quorumId)
                .setTerm(currentTerm)
                .setSuccess(true)
                .build();

        RpcReply reply = new RpcReply(message.getRequest(), m);
        message.reply(reply);
    }

    @FiberOnly
    private void checkOnElection() {
        if (myState == State.LEADER) {
            LOG.debug("{} leader during election check.", myId);
            return;
        }

        if (lastRPC + info.electionTimeout() < info.currentTimeMillis()) {
            LOG.debug("{} Timed out checkin on election, try new election", myId);
            doElection();
        }
    }

    private int calculateMajority(int peerCount) {
        return (int) Math.ceil((peerCount + 1) / 2.0);
    }

    @FiberOnly
    private void doElection() {
        final int majority = calculateMajority(peers.size());
        // Start new election "timer".
        lastRPC = info.currentTimeMillis();
        // increment term.
        setCurrentTerm(currentTerm + 1);
        myState = State.CANDIDATE;

        Raft.RequestVote msg = Raft.RequestVote.newBuilder()
                .setTerm(currentTerm)
                .setCandidateId(myId)
                .setLastLogIndex(log.getLastIndex())
                .setLastLogTerm(log.getLastTerm())
                .build();

        LOG.debug("{} Starting election for currentTerm: {}", myId, currentTerm);

        final long termBeingVotedFor = currentTerm;
        final List<Long> votes = new ArrayList<>();
        for (long peer : peers) {
            // create message:

            RpcRequest req = new RpcRequest(peer, myId, msg);
            AsyncRequest.withOneReply(fiber, sendRpcChannel, req, new Callback<RpcWireReply>() {
                @FiberOnly
                @Override
                public void onMessage(RpcWireReply message) {
                    // if current term has advanced, these replies are stale and should be ignored:

                    if (currentTerm > termBeingVotedFor) {
                        LOG.warn("{} election reply from {}, but currentTerm {} > vote term {}", myId, message.from,
                                termBeingVotedFor, currentTerm);
                        return;
                    }

                    // if we are no longer a Candidate, election was over, these replies are stale.
                   if (myState != State.CANDIDATE) {
                        // we became not, ignore
                        LOG.warn("{} election reply from {} ignored -> in state {}", myId, message.from, myState);
                        return;
                    }

                    Raft.RequestVoteReply reply = message.getRequestVoteReplyMessage();

                    if (reply.getTerm() > currentTerm) {
                        LOG.warn("{} election reply from {}, but term {} was not my term {}, updating currentTerm", myId,
                                message.from, reply.getTerm(), currentTerm);

                        setCurrentTerm(reply.getTerm());
                        return;
                    } else if (reply.getTerm() < currentTerm) {
                        // huh weird.
                        LOG.warn("{} election reply from {}, their term {} < currentTerm {}", myId, reply.getTerm(), currentTerm);
                    }

                    // did you vote for me?
                    if (reply.getVoteGranted()) {
                        // yes!
                        votes.add(message.from);
                    }

                    if (votes.size() > majority ) {

                        becomeLeader();
                    }
                }
            });
        }
    }


    //// Leader timer stuff below
    private Disposable queueConsumer;
    private Disposable appender = null;

    @FiberOnly
    private void haltLeader() {
        myState = State.FOLLOWER;

        stopAppendTimer();
        stopQueueConsumer();
    }

    @FiberOnly
    private void stopQueueConsumer() {
        if (queueConsumer != null) {
            queueConsumer.dispose();
            queueConsumer = null;
        }
    }

    private void becomeLeader() {
        LOG.warn("{} I AM THE LEADER NOW, commece AppendEntries RPCz", myId);

        myState = State.LEADER;

        // Page 7, para 5
        long myNextLog = log.getLastIndex() + 1;

        peerNextIndex = new ArrayList<>(peers.size());
        for (int i = 0; i < peerNextIndex.size() ; i++) {
            peerNextIndex.set(i, myNextLog);
        }

        //startAppendTimer();
        startQueueConsumer();
    }

    @FiberOnly
    private void startQueueConsumer() {
        queueConsumer = fiber.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                consumeQueue();
            }
        }, info.groupCommitDelay(), info.groupCommitDelay(), TimeUnit.MILLISECONDS);
    }

    @FiberOnly
    private void consumeQueue() {
        // retrieve as many items as possible. send rpc.
        ArrayList<IntLogRequest> reqs = new ArrayList<>();

        LOG.debug("{} queue consuming", myId);
        while (logRequests.peek() != null) {
            reqs.add(logRequests.poll());
        }

        LOG.debug("{} {} queue items to commit", myId, reqs.size());

        ArrayList<LogEntry> rpcReqs = new ArrayList<>(reqs.size());
        // we have a list of things now. Commit them to local i guess?
        for (IntLogRequest logReq : reqs) {
            long logNumber = log.logEntry(logReq.datum, currentTerm, logReq.localLogNotification);
            logReq.logNumberNotifation.set(logNumber);

            LogEntry entry = LogEntry.newBuilder()
                    .setTerm(currentTerm)
                    .setIndex(logNumber)
                    .setData(ByteString.copyFrom(logReq.datum))
                    .build();
            rpcReqs.add(entry);
        }

        Raft.AppendEntries msg = Raft.AppendEntries.newBuilder()
                .setTerm(currentTerm)
                .setLeaderId(myId)
                .setPrevLogIndex(log.getLastIndex())
                .setPrevLogTerm(log.getLastTerm())
                .addAllEntries(rpcReqs)
                .setCommitIndex(0)
                .build();

        // after this point, this method will return after dispatching all the async requests.
        // this allows additional commits to flow in, then dispatch their own rpc requests.
        for (Long peer : peers) {
            if (myId == peer) continue; // dont send myself messages.
            RpcRequest request = new RpcRequest(peer, myId, msg);
            AsyncRequest.withOneReply(fiber, sendRpcChannel, request, new Callback<RpcWireReply>() {
                @Override
                public void onMessage(RpcWireReply message) {
                    LOG.debug("{} got a reply {}", myId, message);

                    // calculate durability for the log entries.
                    // calculate most recently visible commit.
                }
            });
        }
    }

    @FiberOnly
    private void startAppendTimer() {
        appender = fiber.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                sendAppendRPC();
            }
        }, 0, info.electionTimeout()/2, TimeUnit.MILLISECONDS);
    }

    @FiberOnly
    private void stopAppendTimer() {
        if (appender != null) {
            appender.dispose();
            appender = null;
        }
    }

    @FiberOnly
    private void sendAppendRPC() {
        Raft.AppendEntries msg = Raft.AppendEntries.newBuilder()
                .setTerm(currentTerm)
                .setLeaderId(myId)
                .setPrevLogIndex(log.getLastIndex())
                .setPrevLogTerm(log.getLastTerm())
                .setCommitIndex(0)
                .build();

        for(Long peer : peers) {
            if (myId == peer) continue; // dont send myself messages.
            RpcRequest request = new RpcRequest(peer, myId, msg);
            AsyncRequest.withOneReply(fiber, sendRpcChannel, request, new Callback<RpcWireReply>() {
                @Override
                public void onMessage(RpcWireReply message) {
                    LOG.debug("{} got a reply {}", myId, message);
               }
            });
        }
    }

    // Small helper methods goeth here

    public RequestChannel<RpcWireRequest, RpcReply> getIncomingChannel() {
        return incomingChannel;
    }

    private void setVotedFor(long votedFor) {
        this.votedFor = votedFor;
    }

    private void setCurrentTerm(long newTerm) {
        this.currentTerm = newTerm;
        // new term, means new attempt to elect.
        setVotedFor(0);
    }

    public long getId() {
        return myId;
    }

    public void dispose() {
        fiber.dispose();
    }

    public boolean isLeader() {
        return myState == State.LEADER;
    }

}
