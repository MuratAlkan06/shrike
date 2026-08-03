package io.shrike.clients;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;

/**
 * A server that answers one connection with whatever bytes a test tells it to, so the client's
 * response guard can be driven with answers no broker would ever send.
 *
 * <p>It accepts exactly one connection, runs the script over it, and then holds the socket open until
 * the test closes this server — a socket that closed itself would look like a broker hanging up,
 * which is a different case with a different exception.
 */
final class ScriptedServer implements AutoCloseable {

    /** How long the accepting thread gets to notice that this server is closing. */
    private static final long STOP_TIMEOUT_SECONDS = 15L;

    /** What one connection is answered with. */
    interface Script {

        void answer(Socket socket) throws IOException;
    }

    private final ServerSocket listener;
    private final Thread thread;
    private final CountDownLatch closing = new CountDownLatch(1);

    /**
     * What the script threw, when it threw. Written by the accepting thread and read by the test's,
     * which is why it is volatile.
     */
    private volatile IOException failure;

    private ScriptedServer(ServerSocket listener, String name, Script script) {
        this.listener = listener;
        this.thread = new Thread(() -> answerOneConnection(script), "shrike-scripted-" + name);
    }

    /**
     * @param name   what to call the thread that serves, so a thread dump reads like documentation
     * @param script what to answer the one connection with
     * @return the started server, listening on a port the operating system chose
     * @throws IOException if the listening socket cannot be opened
     */
    static ScriptedServer start(String name, Script script) throws IOException {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        ScriptedServer server = new ScriptedServer(listener, name, script);
        server.thread.start();
        return server;
    }

    /**
     * @return a configuration pointing a client at this server
     */
    ClientConfig clientConfig(int readTimeoutMillis) {
        return new ClientConfig(listener.getInetAddress().getHostAddress(), listener.getLocalPort(),
                ClientConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS, readTimeoutMillis,
                ClientConfig.DEFAULT_MAX_RESPONSE_BYTES);
    }

    @Override
    public void close() throws IOException {
        closing.countDown();
        listener.close();
        joinTheAcceptingThread();
        if (failure != null) {
            throw failure;
        }
    }

    private void answerOneConnection(Script script) {
        try (Socket socket = listener.accept()) {
            script.answer(socket);
            awaitClosing();
        } catch (IOException e) {
            if (closing.getCount() > 0) {
                // Not the close that stops this server, so the script itself broke: keep it for the
                // test's close() to raise, because failing on this thread would fail nothing.
                failure = e;
            }
        }
    }

    private void awaitClosing() {
        try {
            closing.await(STOP_TIMEOUT_SECONDS, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinTheAcceptingThread() {
        try {
            thread.join(SECONDS.toMillis(STOP_TIMEOUT_SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while stopping " + thread.getName());
        }
        if (thread.isAlive()) {
            fail(thread.getName() + " did not stop within " + STOP_TIMEOUT_SECONDS + "s");
        }
    }
}
