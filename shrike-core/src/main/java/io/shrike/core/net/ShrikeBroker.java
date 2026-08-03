package io.shrike.core.net;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import io.shrike.core.group.GroupOffsetStore;
import io.shrike.core.log.ShrikeIOException;
import io.shrike.core.protocol.RequestReader;
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
 * a thousand sockets costs a thousand closes rather than a thousand threads.
 *
 * <p><strong>Starting.</strong> The topic registry and every partition log are opened and recovered
 * first, then the committed offsets are loaded, then the socket binds, then the acceptor starts, and
 * only then is the {@link ReadyFile} written. A reader that can see the ready file can connect.
 *
 * <p><strong>Stopping.</strong> Stop accepting, wake every fetch that is waiting on a partition, close
 * the open connections so their threads come out of their blocking reads, join those threads under a
 * bounded deadline, and close every log — which forces the segment it was still writing. Nothing is
 * deleted, including the ready file.
 *
 * <p><strong>Durability.</strong> A produce is acknowledged once its bytes are with the operating
 * system, not once they are on the device: that is what {@code SegmentedLog.append} promises and this
 * broker promises nothing more. A commit is different and stronger — it is acknowledged only after its
 * file has been forced and atomically moved into place. A configurable flush mode is a later slice,
 * and until it exists this paragraph is the whole durability claim.
 *
 * <p><strong>Binding.</strong> The listener binds the loopback interface. This build has no
 * authentication and no transport security, so the port it listens on is a port anything that can
 * reach it may write to; a bind address belongs in the same slice as whatever makes exposing it
 * defensible.
 */
public final class ShrikeBroker implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(ShrikeBroker.class.getName());

    /** The name of the one thread that accepts, and the prefix every connection thread's name takes. */
    static final String ACCEPTOR_THREAD_NAME = "shrike-acceptor";

    static final String CONNECTION_THREAD_PREFIX = "shrike-conn-";

    /** How long stopping waits for a thread to notice its channel is closed before saying it did not. */
    private static final long STOP_TIMEOUT_MILLIS = 10_000L;

    /**
     * How many accepts may fail in a row before the acceptor gives up. An accept that fails for a
     * reason other than the socket closing — the process is out of file descriptors, say — would
     * otherwise fail again immediately, and a loop that logs as fast as it can is worse than a broker
     * that stops taking new connections and says so.
     */
    private static final int ACCEPT_FAILURE_LIMIT = 16;

    private final BrokerConfig config;
    private final ServerSocketChannel serverChannel;
    private final TopicRegistry topics;
    private final GroupOffsetStore groupOffsets;
    private final RequestDispatcher dispatcher;
    private final int port;

    /**
     * Read by the acceptor on every pass and set once by {@link #close()}. An {@link AtomicBoolean}
     * rather than a volatile field because stopping must happen once even if two threads ask for it.
     */
    private final AtomicBoolean stopping = new AtomicBoolean();

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
     * The one accepting thread. Not final because it starts after the broker is built, since a thread
     * handed a half-built broker would be the worse trade; volatile because {@link #close()} may be
     * called from a thread that never watched it being set.
     */
    private volatile Thread acceptor;

    private ShrikeBroker(BrokerConfig config, ServerSocketChannel serverChannel, TopicRegistry topics,
                         GroupOffsetStore groupOffsets, int port) {
        this.config = config;
        this.serverChannel = serverChannel;
        this.topics = topics;
        this.groupOffsets = groupOffsets;
        this.dispatcher = new RequestDispatcher(topics, groupOffsets);
        this.port = port;
    }

    /**
     * Recovers what is in the data directory, binds the port, starts accepting, and writes the ready
     * file.
     *
     * @param config     where things live and how much of them there may be
     * @param timeSource the clock that stamps appended records and bounds every fetch's wait
     * @return the running broker, which the caller closes
     * @throws ShrikeIOException if the data directory, a log, the socket, or the ready file cannot be
     *                           opened or written
     */
    public static ShrikeBroker start(BrokerConfig config, TimeSource timeSource) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(timeSource, "timeSource");

        Path dataDirectory = config.dataDirectory();
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot create the data directory " + dataDirectory, e);
        }

        TopicRegistry topics = TopicRegistry.open(dataDirectory, timeSource, config.logConfig(),
                config.maxRequestBytes());
        ShrikeBroker broker;
        try {
            GroupOffsetStore groupOffsets = GroupOffsetStore.open(dataDirectory);
            ServerSocketChannel serverChannel = bind(config);
            broker = new ShrikeBroker(config, serverChannel, topics, groupOffsets, boundPort(serverChannel));
        } catch (RuntimeException e) {
            topics.close();
            throw e;
        }

        try {
            broker.startAccepting();
            ReadyFile.write(config.readyFilePath(), broker.port, ProcessHandle.current().pid());
        } catch (RuntimeException e) {
            broker.close();
            throw e;
        }

        LOGGER.log(System.Logger.Level.INFO, () -> "shrike is listening on port " + broker.port + ", data directory "
                + dataDirectory + ", ready file " + config.readyFilePath());
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

        closeQuietly(serverChannel, "the listening socket");
        joinBounded(acceptor, STOP_TIMEOUT_MILLIS);

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

    private void startAccepting() {
        acceptor = new Thread(this::acceptConnections, ACCEPTOR_THREAD_NAME);
        acceptor.start();
    }

    /**
     * The one thing {@code shrike-acceptor} does. It never reads a byte and never waits on a
     * connection: a socket it accepts is either handed to a thread of its own or closed on the spot.
     */
    private void acceptConnections() {
        int consecutiveFailures = 0;
        while (!stopping.get()) {
            SocketChannel socket;
            try {
                socket = serverChannel.accept();
                consecutiveFailures = 0;
            } catch (ClosedChannelException e) {
                return;
            } catch (IOException e) {
                consecutiveFailures++;
                if (consecutiveFailures >= ACCEPT_FAILURE_LIMIT) {
                    LOGGER.log(System.Logger.Level.ERROR, ACCEPTOR_THREAD_NAME + " gave up after "
                            + consecutiveFailures + " accepts in a row failed; this broker serves the connections it"
                            + " has and takes no more", e);
                    return;
                }
                LOGGER.log(System.Logger.Level.WARNING, ACCEPTOR_THREAD_NAME + " could not accept a connection", e);
                continue;
            }
            serveOrClose(socket);
        }
    }

    /**
     * Gives one accepted socket a thread, or closes it because the broker is full or on its way down.
     * A refused connection is closed here, on the acceptor, which is the cheapest thing that can happen
     * to it.
     */
    private void serveOrClose(SocketChannel socket) {
        if (stopping.get() || !reserveConnection()) {
            // DEBUG rather than WARNING on purpose: this line is written by whoever is connecting, and
            // a caller that can make the broker log as fast as it can open sockets can fill a disk.
            LOGGER.log(System.Logger.Level.DEBUG, () -> "closed a connection without serving it: "
                    + (stopping.get() ? "this broker is stopping" : "connectionCap=" + config.connectionCap()
                    + " connections are already open"));
            closeQuietly(socket, "a connection this broker would not serve");
            return;
        }

        String name = CONNECTION_THREAD_PREFIX + nextConnectionNumber.getAndIncrement();
        try {
            // Small requests and small answers, one at a time: waiting to see whether another write is
            // coming would add a round trip's delay to every response.
            socket.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
        } catch (IOException e) {
            LOGGER.log(System.Logger.Level.WARNING, name + " could not be configured, so it was closed", e);
            closeQuietly(socket, name);
            openConnectionCount.decrementAndGet();
            return;
        }

        Connection connection = new Connection(name, socket, new RequestReader(config.maxRequestBytes()), dispatcher);
        Thread thread = new Thread(() -> {
            try {
                connection.serve();
            } finally {
                openConnections.remove(connection);
                openConnectionCount.decrementAndGet();
            }
        }, name);
        openConnections.put(connection, thread);
        thread.start();
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

    private static ServerSocketChannel bind(BrokerConfig config) {
        ServerSocketChannel serverChannel = null;
        try {
            serverChannel = ServerSocketChannel.open();
            serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
            // The backlog matches the connection cap: sockets the operating system holds for a broker
            // that is already full are sockets it will only close.
            serverChannel.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.port()),
                    config.connectionCap());
            return serverChannel;
        } catch (IOException e) {
            closeQuietly(serverChannel, "the listening socket");
            throw new ShrikeIOException("cannot listen on port " + config.port(), e);
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
