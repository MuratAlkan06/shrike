package io.shrike.core.net;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.shrike.core.flush.FlushSweep;
import io.shrike.core.group.GroupOffsetStore;
import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.RequestReader;
import io.shrike.core.retention.RetentionSweep;
import io.shrike.core.time.TimeSource;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The broker: a listening socket, a thread per connection, and the partitions and committed offsets
 * they act on.
 *
 * <p><strong>Threads.</strong> One platform thread named {@code shrike-acceptor} does nothing but
 * accept. Each accepted connection gets one platform thread of its own named
 * {@code shrike-conn-<n>}, where {@code n} only ever climbs, so a log line and a thread dump name the
 * same connection an hour apart. Connections are capped: at
 * {@link BrokerConfig#connectionCap()} the next socket is accepted and immediately closed. Accepting
 * and closing is the point — the acceptor never blocks and nothing is queued, so a client that opens
 * a thousand sockets costs a thousand closes rather than a thousand threads. Three more threads:
 * {@code shrike-retention} does nothing but ask each partition to delete the segments retention no
 * longer keeps — with retention off, which is the default, it asks and there is nothing to do —
 * {@code shrike-flush} does nothing but ask each partition to force the records that have sat unforced
 * for {@code flush.interval.ms} — in {@code per-record} mode it asks and there is nothing to do,
 * because the append already forced — and {@code shrike-conn-reaper} does nothing but ask each open
 * connection whether it has spent longer than {@code read.timeout.ms} reading one request.
 *
 * <p><strong>Starting.</strong> The topic registry and every partition log are opened and recovered
 * first, then the committed offsets are loaded, then the socket binds, then the acceptor, the
 * retention thread, the flush thread, and the connection reaper start, and only then is the
 * {@link ReadyFile} written. A reader that can see the ready file can connect.
 *
 * <p><strong>Stopping.</strong> Stop accepting, stop retention, the flush interval, and the reaper and
 * wait for a pass of each in flight, wake every fetch that is waiting on a partition, close the open
 * connections so their threads come out of their blocking reads, join those threads under a bounded
 * deadline, and close every log — which forces the segment it was still writing. Nothing is deleted on
 * the way out, including the ready file: what retention deletes it deletes while the broker is running
 * and says so in the log.
 *
 * <p><strong>Durability.</strong> What a produce promises is decided by {@code flush.mode} and by
 * nothing else. Under {@link io.shrike.core.log.FlushMode#PER_RECORD} a produce is acknowledged only
 * after {@code force()}, so an acknowledged record survives an operating-system or power failure.
 * Under {@link io.shrike.core.log.FlushMode#INTERVAL}, the default, acknowledged records survive a
 * process crash (they are in the page cache), but up to one flush interval of acknowledged records can
 * be lost on an operating-system or power failure, after which recovery truncates the torn tail. A
 * commit is neither of those and is stronger than both — it is acknowledged only after its file has
 * been forced and atomically moved into place. None of this is a statement about delivery, which is
 * at-least-once whichever mode this broker runs in.
 *
 * <p><strong>Binding.</strong> {@link #start(BrokerConfig, TimeSource)} binds the loopback interface,
 * and {@link #start(BrokerConfig, TimeSource, InetAddress)} binds the address it is handed — the only
 * way this broker comes to listen anywhere else. This build has no authentication and no transport
 * security, so a port it listens on past loopback is a port anything that can reach it may write to,
 * and a caller that names a wider address is saying it has arranged that reachability itself.
 *
 * <p><strong>Reading and idling are bounded; serving is not.</strong> A connection may spend at most
 * {@link BrokerConfig#readTimeoutMs()} in one read phase — sitting idle between requests, or part way
 * through a request frame — and {@code shrike-conn-reaper} closes it when it spends more, which is what
 * brings its thread out of the blocking read and gives its place under
 * {@link BrokerConfig#connectionCap()} back. {@code SO_TIMEOUT} is not what does it: that bounds reads
 * on a socket's streams and not on the {@code SocketChannel} this broker reads through. The bound is
 * deliberately not a bound on the connection: a fetch held open for {@code max.fetch.wait.ms} is this
 * broker waiting to write rather than a client failing to speak, and it is never closed for it.
 * Authentication is still not here, and #9 is still where the rest of that is tracked.
 */
public final class ShrikeBroker implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(ShrikeBroker.class.getName());

    /** The name of the one thread that accepts, and the prefix every connection thread's name takes. */
    static final String ACCEPTOR_THREAD_NAME = "shrike-acceptor";

    static final String CONNECTION_THREAD_PREFIX = "shrike-conn-";

    /** How long stopping waits for a thread to notice its channel is closed before saying it did not. */
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;

    /**
     * How long the acceptor waits on the stop signal after an accept fails. An accept that fails for a
     * reason other than the socket closing — the process is out of file descriptors, say — would
     * otherwise fail again immediately, and a loop that logs as fast as it can is its own outage. This
     * is not a sleep for coordination: it is a bounded wait whose predicate is "this broker is
     * stopping", so a shutdown during a backoff ends the acceptor at once rather than after it.
     */
    private static final long ACCEPT_BACKOFF_MILLIS = 100L;

    private final BrokerConfig config;
    private final ServerSocketChannel serverChannel;
    private final TopicRegistry topics;
    private final GroupOffsetStore groupOffsets;
    private final RequestDispatcher dispatcher;

    /** The {@code shrike-retention} thread and its schedule; built here, started by {@link #start}. */
    private final RetentionSweep retention;

    /**
     * The {@code shrike-flush} thread and its schedule; built here, started by {@link #start}. It asks
     * exactly as often as {@code flush.interval.ms}, which is what keeps the window of unforced records
     * at one interval rather than at two.
     */
    private final FlushSweep flush;

    /**
     * The {@code shrike-conn-reaper} thread and its schedule; built here, started by {@link #start}. It
     * asks exactly as often as {@code read.timeout.ms}, so a connection that stalls holds its slot for
     * between one and two bounds rather than for as long as its client stays connected.
     */
    private final ConnectionReaper reaper;

    /** Handed to each connection, so every read phase is stamped from the clock this broker was given. */
    private final TimeSource timeSource;

    private final int port;

    /**
     * Read by the acceptor on every pass and set once by {@link #close()}. An {@link AtomicBoolean}
     * rather than a volatile field because stopping must happen once even if two threads ask for it.
     */
    private final AtomicBoolean stopping = new AtomicBoolean();

    /**
     * Counted down once, by {@link #close()}, immediately after {@link #stopping} is set. It is what a
     * backing-off acceptor waits on, so the wait ends the moment the predicate it is about becomes true
     * instead of when its timeout runs out.
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * The connections being served, and the thread serving each. The acceptor is the only thread that
     * adds; each connection's own thread removes itself as it ends. Concurrent because those are
     * different threads and stopping walks the map while both may be happening.
     */
    private final Map<Connection, Thread> openConnections = new ConcurrentHashMap<>();

    /**
     * How many connections are being served. Only the acceptor increases it, and only under the
     * compare-and-set in {@link #reserveConnection()}, so the cap is never crossed even for an instant;
     * each connection thread decreases it as it ends.
     */
    private final AtomicInteger openConnectionCount = new AtomicInteger();

    /** Never reused, so two connections an hour apart cannot share a name. */
    private final AtomicLong nextConnectionNumber = new AtomicLong(1L);

    /**
     * A test seam, and the only field here that production code never writes. It runs on the acceptor
     * during a handoff, after the connection's place under the cap has been taken and before anything
     * has been allocated for it — the narrowest window in which a failure could leak that place, the
     * socket, or the acceptor itself. A test makes it throw the way running out of memory would;
     * production leaves it doing nothing.
     */
    // volatile because a test installs it from its own thread and shrike-acceptor is what reads it.
    private volatile Runnable connectionReserved = () -> {
    };

    /**
     * The second test seam, and the second field here production code never writes. It runs on a
     * connection's own thread as that connection ends, after its map entry and its place under the cap
     * have both gone back — the one instant a test can be sure a slot is free again, which nothing on
     * the wire says. A reaped connection gives its slot back on its own thread rather than on the one
     * that closed it, so without this a test proving the cap does not shrink would have to guess when to
     * look. Production leaves it doing nothing.
     */
    // volatile because a test installs it from its own thread and every connection thread reads it.
    private volatile Runnable connectionEnded = () -> {
    };

    /**
     * The one accepting thread. Not final because it starts after the broker is built, since a thread
     * handed a half-built broker would be the worse trade; volatile because {@link #close()} may be
     * called from a thread that never watched it being set.
     */
    private volatile Thread acceptor;

    private ShrikeBroker(BrokerConfig config, ServerSocketChannel serverChannel, TopicRegistry topics,
                         GroupOffsetStore groupOffsets, TimeSource timeSource, int port) {
        this.config = config;
        this.serverChannel = serverChannel;
        this.topics = topics;
        this.groupOffsets = groupOffsets;
        this.dispatcher = new RequestDispatcher(topics, groupOffsets);
        this.retention = new RetentionSweep(topics, timeSource, RetentionSweep.DEFAULT_CHECK_INTERVAL_MILLIS);
        this.flush = new FlushSweep(topics, timeSource, config.logConfig().flushIntervalMs());
        this.timeSource = timeSource;
        this.port = port;
        // Last, and holding a method of a broker that is one statement from being built: the reaper only
        // stores it here, and the thread that would call it does not exist until start().
        this.reaper = new ConnectionReaper(this::closeStalledConnections, timeSource, config.readTimeoutMs());
    }

    /**
     * Recovers what is in the data directory, binds the port on the loopback interface, starts
     * accepting, and writes the ready file.
     *
     * @param config     where things live and how much of them there may be
     * @param timeSource the clock that stamps appended records and bounds every fetch's wait
     * @return the running broker, which the caller closes
     * @throws ShrikeIOException if the data directory, a log, the socket, or the ready file cannot be
     *                           opened or written
     */
    public static ShrikeBroker start(BrokerConfig config, TimeSource timeSource) {
        return start(config, timeSource, InetAddress.getLoopbackAddress());
    }

    /**
     * The same start, listening on an address the caller names instead of the loopback one.
     *
     * <p>Widening the bind has a signature of its own because it is a decision rather than a setting.
     * Nothing this build ships calls it with anything but loopback except {@link BrokerMain}, and
     * {@link BrokerMain} does it only when the environment it was started in says so in so many words:
     * a broker reachable from another host is a broker anything that can reach the port may append to,
     * since there is no authentication, no authorization, and no transport security here. The case this
     * exists for is a container, which binds every interface inside a network namespace of its own and
     * is reached through a port its operator published on purpose.
     *
     * @param config      where things live and how much of them there may be
     * @param timeSource  the clock that stamps appended records and bounds every fetch's wait
     * @param bindAddress the interface to listen on
     * @return the running broker, which the caller closes
     * @throws ShrikeIOException if the data directory, a log, the socket, or the ready file cannot be
     *                           opened or written
     */
    public static ShrikeBroker start(BrokerConfig config, TimeSource timeSource, InetAddress bindAddress) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(timeSource, "timeSource");
        Objects.requireNonNull(bindAddress, "bindAddress");

        Path dataDirectory = config.dataDirectory();
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot create the data directory " + dataDirectory, e);
        }

        TopicRegistry topics = TopicRegistry.open(config, timeSource);
        ShrikeBroker broker;
        try {
            GroupOffsetStore groupOffsets = GroupOffsetStore.open(dataDirectory);
            ServerSocketChannel serverChannel = bind(config, bindAddress);
            broker = new ShrikeBroker(config, serverChannel, topics, groupOffsets, timeSource,
                    boundPort(serverChannel));
        } catch (RuntimeException e) {
            topics.close();
            throw e;
        }

        try {
            broker.retention.start();
            broker.flush.start();
            broker.reaper.start();
            broker.startAccepting();
            ReadyFile.write(config.readyFilePath(), broker.port, ProcessHandle.current().pid());
        } catch (RuntimeException e) {
            broker.close();
            throw e;
        }

        LOGGER.log(System.Logger.Level.INFO, () -> "shrike is listening on " + bindAddress.getHostAddress() + " port "
                + broker.port + ", data directory " + dataDirectory + ", ready file " + config.readyFilePath());
        return broker;
    }

    /**
     * @return the port the broker actually bound, which is the one the operating system chose when the
     *         configured port was {@value BrokerConfig#EPHEMERAL_PORT}
     */
    public int port() {
        return port;
    }

    /**
     * Stops accepting, ends every open connection, and closes every log. Calling it twice does nothing
     * the second time.
     *
     * @throws ShrikeIOException if a log cannot be closed; every other log is closed first
     */
    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        stopSignal.countDown();

        closeQuietly(serverChannel, "the listening socket");
        joinBounded(acceptor, STOP_TIMEOUT_MILLIS);
        // Before the logs are closed, because a pass of either in flight is holding a partition lock
        // and is about to unlink a file or force a channel: waiting for both here is what keeps a
        // shutdown from racing them. Closing a log forces it anyway, so nothing is lost by stopping the
        // flush interval first.
        retention.close();
        flush.close();
        // Beside the other two, and for the plainer reason: a pass in flight only closes sockets a
        // shutdown is about to close anyway, so what this waits for is the thread rather than the pass.
        reaper.close();

        // Woken before their sockets are closed, so a fetch that is part way through a long poll
        // returns rather than being joined against for a deadline it was told it could use.
        topics.stopServing();
        for (Connection connection : openConnections.keySet()) {
            connection.close();
        }

        // Elapsed time, not wall-clock time, and so not the injected clock: how long a shutdown has
        // been waiting for a thread is a fact about this machine that no test may freeze.
        long deadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(STOP_TIMEOUT_MILLIS);
        for (Map.Entry<Connection, Thread> open : openConnections.entrySet()) {
            long remainingMillis = NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            joinBounded(open.getValue(), Math.max(1L, remainingMillis));
        }

        LOGGER.log(System.Logger.Level.INFO, () -> "shrike stopped listening on port " + port);
        topics.close();
    }

    /**
     * The live partition behind a topic and a partition number.
     *
     * <p>Package-private because it is a seam for this package's own tests: a test that has to land an
     * append inside a waiting fetch's window needs the partition that fetch is waiting on, and there is
     * no way to say that over a socket. Nothing outside the broker can reach it.
     *
     * @param topic     a topic name
     * @param partition a partition number
     * @return that partition, or empty when the broker holds no such thing
     */
    Optional<Partition> partition(String topic, int partition) {
        return topics.partition(topic, partition);
    }

    /**
     * The committed offsets this broker loaded when it started. Package-private for the same reason as
     * {@link #partition(String, int)}: no api reads a committed offset back yet, so a test that proves
     * a restart kept one has to ask the broker itself.
     *
     * @return the store
     */
    GroupOffsetStore groupOffsets() {
        return groupOffsets;
    }

    /**
     * Installs the test seam described on {@link #connectionReserved}. Package-private for the same
     * reason as {@link #partition(String, int)}: there is no way to say "fail this handoff" over a
     * socket, and a broker that could be told so from outside would be a broker with a way to be
     * emptied.
     *
     * @param seam what to run during a handoff, once that connection's place under the cap is taken
     */
    void onConnectionReserved(Runnable seam) {
        Objects.requireNonNull(seam, "seam");
        connectionReserved = seam;
    }

    /**
     * Installs the test seam described on {@link #connectionEnded}. Package-private for the same reason
     * as {@link #onConnectionReserved(Runnable)}.
     *
     * @param seam what to run on a connection's own thread once that connection has given back
     *             everything it held
     */
    void onConnectionEnded(Runnable seam) {
        Objects.requireNonNull(seam, "seam");
        connectionEnded = seam;
    }

    private void startAccepting() {
        acceptor = new Thread(this::acceptConnections, ACCEPTOR_THREAD_NAME);
        acceptor.start();
    }

    /**
     * The one thing {@code shrike-acceptor} does. It never reads a byte and never waits on a
     * connection: a socket it accepts is either handed to a thread of its own or closed on the spot.
     *
     * <p>An accept that fails while this broker is running is treated as the transient thing it usually
     * is — the process is out of file descriptors, a connection was reset between the kernel's queue and
     * this call — so the acceptor backs off and tries again rather than counting failures towards a
     * number that ends it. Exhausting descriptors is a condition that clears on its own as connections
     * close, and an acceptor that had given up would turn it into an outage that lasts until somebody
     * restarts the broker. A handoff that fails is the same kind of thing and is treated the same way:
     * {@link #serveOrClose(SocketChannel)} throws nothing at all — it gives back the place under the cap
     * and the socket, and says whether the failure was about this process rather than about that one
     * socket, in which case the acceptor backs off too. The loop ends when the broker is stopping, and
     * only then.
     */
    private void acceptConnections() {
        long consecutiveFailures = 0L;
        while (!stopping.get()) {
            SocketChannel socket;
            try {
                socket = serverChannel.accept();
                consecutiveFailures = 0L;
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                if (stopping.get()) {
                    return;
                }
                consecutiveFailures++;
                LOGGER.log(System.Logger.Level.WARNING, ACCEPTOR_THREAD_NAME + " could not accept a connection ("
                        + consecutiveFailures + " in a row), so it waits " + ACCEPT_BACKOFF_MILLIS
                        + "ms and accepts again", e);
                awaitStopFor(ACCEPT_BACKOFF_MILLIS);
                continue;
            }
            if (!serveOrClose(socket)) {
                awaitStopFor(ACCEPT_BACKOFF_MILLIS);
            }
        }
    }

    /**
     * Waits for this broker to start stopping, for at most {@code timeoutMillis}. The predicate is the
     * stop signal itself, so this returns immediately once {@link #close()} has begun.
     */
    private void awaitStopFor(long timeoutMillis) {
        try {
            stopSignal.await(timeoutMillis, MILLISECONDS);
        } catch (InterruptedException e) {
            // Somebody wants this thread to stop waiting. Stop waiting, and leave the flag for them.
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Gives one accepted socket a thread, or closes it because the broker is full, on its way down, or
     * unable to hand it over at all. A refused connection is closed here, on the acceptor, which is the
     * cheapest thing that can happen to it.
     *
     * <p>Everything from the moment a place under the cap is taken is covered by one unwind, and no
     * throwable leaves this method. That is not defensive habit: constructing the connection,
     * constructing the thread, registering it, and starting it can each fail with an
     * {@code OutOfMemoryError} under exactly the pressure where a leaked place under the cap, a leaked
     * map entry, or a leaked socket costs the most — and a throwable that reached the accept loop would
     * end the one thread this broker accepts on while its port stayed bound and its ready file stayed
     * where it was, which is a broker that is up and deaf.
     *
     * @param socket the accepted socket
     * @return whether the next socket may be accepted straight away. False means the handoff failed for
     *         a reason that is about this process rather than about this socket — out of memory, out of
     *         native threads — so the acceptor waits on the stop signal before it accepts again rather
     *         than meeting the same wall at the speed of a loop
     */
    private boolean serveOrClose(SocketChannel socket) {
        if (stopping.get() || !reserveConnection()) {
            // DEBUG rather than WARNING on purpose: this line is written by whoever is connecting, and
            // a caller that can make the broker log as fast as it can open sockets can fill a disk.
            LOGGER.log(System.Logger.Level.DEBUG, () -> "closed a connection without serving it: "
                    + (stopping.get() ? "this broker is stopping" : "connectionCap=" + config.connectionCap()
                    + " connections are already open"));
            closeQuietly(socket, "a connection this broker would not serve");
            return true;
        }

        String name = CONNECTION_THREAD_PREFIX + nextConnectionNumber.getAndIncrement();
        // Null until there is a connection to give back, because a failure before that has only the
        // socket and the place under the cap to return.
        Connection reserved = null;
        try {
            connectionReserved.run();

            // Small requests and small answers, one at a time: waiting to see whether another write is
            // coming would add a round trip's delay to every response.
            socket.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);

            Connection connection = new Connection(name, socket, new RequestReader(config.maxRequestBytes()),
                    dispatcher, timeSource);
            reserved = connection;
            Thread thread = new Thread(() -> {
                try {
                    connection.serve();
                } finally {
                    openConnections.remove(connection);
                    openConnectionCount.decrementAndGet();
                    connectionEnded.run();
                }
            }, name);

            // Registered before the check, not after it, and that order is the whole guard — unchanged
            // by the unwind around it, which only ever removes this entry again. close() sets stopping
            // before it walks this map, so a close that walked the map without seeing this entry had not
            // set stopping yet when it walked — and then this read sees it. One of the two closes the
            // connection, and neither can leave it open with nobody left to close it.
            openConnections.put(connection, thread);
            if (stopping.get()) {
                abandon(connection, socket);
                return true;
            }

            // The last statement the unwind covers, and one of the reasons it exists: a start that
            // throws leaves a map entry and a place under the cap that the thread body was going to give
            // back and now never will.
            thread.start();
            return true;
        } catch (IOException e) {
            // About this socket rather than about this broker — the peer went away between the accept
            // and here — so the next one is worth accepting at once.
            LOGGER.log(System.Logger.Level.WARNING, name + " could not be configured, so it was closed", e);
            abandon(reserved, socket);
            return true;
        } catch (Throwable e) {
            // Out of memory, out of native threads: the pressure case this unwinding exists for, at the
            // moment a place under the cap and a socket are worth most. Everything this connection took
            // goes back, and the acceptor stays alive to serve the connections that are already open.
            LOGGER.log(System.Logger.Level.WARNING, name + " could not be handed a thread, so it was closed", e);
            abandon(reserved, socket);
            return false;
        }
    }

    /**
     * Gives back everything {@link #serveOrClose(SocketChannel)} took for a connection that will not be
     * served: its map entry, its place under the cap, and its socket. It runs at most once per
     * connection, because the thread that would otherwise give those back either never started or is
     * not going to.
     *
     * @param connection the connection, or null when the handoff failed before there was one, in which
     *                   case there is no map entry and the socket is what there is to close
     * @param socket     the accepted socket
     */
    private void abandon(Connection connection, SocketChannel socket) {
        if (connection == null) {
            closeQuietly(socket, "a connection this broker could not hand to a thread");
        } else {
            openConnections.remove(connection);
            connection.close();
        }
        openConnectionCount.decrementAndGet();
    }

    /**
     * One pass of the read bound: close every open connection that has spent at least
     * {@code read.timeout.ms} in one read phase, and leave the rest alone. It is what
     * {@code shrike-conn-reaper} calls on its own thread, and what a test calls directly with a clock it
     * advanced, because when a pass happens is not what the bound means.
     *
     * <p>Nothing here gives a slot back. Closing the socket is what brings a connection's own thread out
     * of its blocking read, and that thread removes its map entry and its place under the cap on the way
     * out, exactly as it does for a client that hung up.
     *
     * @param nowMillis the epoch millisecond every read phase's age is measured against
     * @return how many connections this pass closed
     */
    int closeStalledConnections(long nowMillis) {
        int closed = 0;
        for (Connection connection : openConnections.keySet()) {
            if (connection.closeIfStalled(nowMillis, config.readTimeoutMs())) {
                closed++;
            }
        }
        return closed;
    }

    /**
     * @return whether this connection may be served, having counted it when it may. The acceptor is
     *         the only caller, but the loop is exact rather than relying on that: it never lets the
     *         count pass the cap even between two instructions
     */
    private boolean reserveConnection() {
        while (true) {
            int open = openConnectionCount.get();
            if (open >= config.connectionCap()) {
                return false;
            }
            if (openConnectionCount.compareAndSet(open, open + 1)) {
                return true;
            }
        }
    }

    private static ServerSocketChannel bind(BrokerConfig config, InetAddress bindAddress) {
        ServerSocketChannel serverChannel = null;
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
            // The backlog matches the connection cap: sockets the operating system holds for a broker
            // that is already full are sockets it will only close.
            serverChannel.bind(new InetSocketAddress(bindAddress, config.port()), config.connectionCap());
            return serverChannel;
        } catch (IOException e) {
            closeQuietly(serverChannel, "the listening socket");
            throw new ShrikeIOException("cannot listen on " + bindAddress.getHostAddress() + " port " + config.port(),
                    e);
        }
    }

    private static int boundPort(ServerSocketChannel serverChannel) {
        try {
            return ((InetSocketAddress) serverChannel.getLocalAddress()).getPort();
        } catch (IOException e) {
            closeQuietly(serverChannel, "the listening socket");
            throw new ShrikeIOException("cannot read back the port that was bound", e);
        }
    }

    private static void joinBounded(Thread thread, long timeoutMillis) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            // Somebody wants this shutdown to stop waiting. Stop waiting, and leave the flag for them.
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive()) {
            LOGGER.log(System.Logger.Level.WARNING, () -> thread.getName() + " did not stop within " + timeoutMillis
                    + "ms, so this broker stopped waiting for it");
        }
    }

    private static void closeQuietly(Closeable closeable, String what) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, "cannot close " + what, e);
        }
    }
}
