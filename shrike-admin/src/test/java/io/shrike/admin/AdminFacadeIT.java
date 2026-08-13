package io.shrike.admin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.clients.Await;
import io.shrike.clients.BrokerProcessMain;
import io.shrike.clients.ClientConfig;
import io.shrike.clients.JavaProcess;
import io.shrike.clients.ScriptedServer;
import io.shrike.clients.ShrikeConsumer;
import io.shrike.clients.ShrikeProducer;
import io.shrike.clients.ShrikeTopics;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * The facade against a real broker in a process of its own, over real HTTP.
 *
 * <p>Nothing is shared between the two but a TCP socket: the broker runs in another JVM, with a data
 * directory this test chose and the facade has never heard of, and every number the facade reports has
 * to have crossed the wire protocol to get here. A facade that read the data directory instead would
 * fail no assertion below — which is exactly why the broker is a separate process, so that the claim
 * "it is a client like any other" is something the test can enforce rather than something the code
 * says about itself.
 *
 * <p>The expected bodies are written out whole. A JSON shape is a published contract, and a test that
 * checks it field by field would keep passing while the shape drifted.
 */
class AdminFacadeIT {

    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    /** All a chunked answer carrying nothing consists of: one chunk of length zero, and the end. */
    private static final String CHUNKED_END_OF_BODY = "0\r\n\r\n";

    private static final String ORDERS = "orders";
    private static final String SHIPMENTS = "shipments";
    private static final String GROUP = "watchers";

    private static final int ORDERS_PARTITION_COUNT = 3;
    private static final int SHIPMENTS_PARTITION_COUNT = 2;

    /** How many requests in a row count as a burst: enough that a line per request would show. */
    private static final int BURST_REQUEST_COUNT = 5;

    /** The bound this facade gives a broker to answer a describe, from {@code application.properties}. */
    private static final int DESCRIBE_READ_TIMEOUT_MILLIS = 5_000;

    /** How long a connection may hold a place under the connection cap without asking anything. */
    private static final int CONNECTION_TIMEOUT_MILLIS = 10_000;

    /**
     * The upper bound on giving up on a broker that will not answer. It is neither the bound being
     * tested nor near it: it sits between the five seconds this facade waits and the thirty a client
     * waits by default, far enough from both that a loaded machine cannot move a passing run across
     * it, and it fails only if this facade is waiting the client default again.
     */
    private static final long GIVING_UP_BOUND_MILLIS = 20_000L;

    @TempDir
    Path workingDirectory;

    /** Every process this test started, so that every one of them is destroyed however it ends. */
    private final List<JavaProcess> started = new ArrayList<>();

    private final HttpClient http = HttpClient.newHttpClient();

    private ConfigurableApplicationContext facade;
    private int facadePort;

    /**
     * Runs whatever the test did — passing, failing an assertion, or timing out inside a bounded
     * wait — because a leaked broker holds a port and a leaked facade holds another.
     */
    @AfterEach
    void stopTheFacadeAndDestroyEveryProcessThisTestStarted() {
        if (facade != null) {
            facade.close();
        }
        http.close();
        for (JavaProcess process : started) {
            process.destroyTree();
        }
    }

    @Test
    void reportsTheTopicsPartitionsAndGroupLagOfTheBrokerItIsPointedAt() throws Exception {
        int brokerPort = startBroker();
        fillTheBroker(brokerPort);
        startFacade(brokerPort);

        HttpResponse<String> listing = get("/api/v1/topics");
        HttpResponse<String> orders = get("/api/v1/topics/orders");
        HttpResponse<String> lag = get("/api/v1/groups/watchers/lag");

        assertEquals(200, listing.statusCode(), listing.body());
        assertTrue(contentTypeOf(listing).startsWith("application/json"), contentTypeOf(listing));
        assertEquals("""
                {"topics":[{"name":"orders","partitionCount":3},{"name":"shipments","partitionCount":2}]}""",
                listing.body());

        assertEquals(200, orders.statusCode(), orders.body());
        assertEquals("""
                {"name":"orders","partitions":[\
                {"partition":0,"logStartOffset":0,"highWaterMark":5,"segmentCount":1,"bytes":%d},\
                {"partition":1,"logStartOffset":0,"highWaterMark":2,"segmentCount":1,"bytes":%d},\
                {"partition":2,"logStartOffset":0,"highWaterMark":0,"segmentCount":1,"bytes":%d}]}"""
                .formatted(bytesOnDisk(ORDERS, 0), bytesOnDisk(ORDERS, 1), bytesOnDisk(ORDERS, 2)),
                orders.body(),
                "every number came over the wire, and the bytes match what the broker's files actually occupy");

        assertEquals(200, lag.statusCode(), lag.body());
        assertEquals("""
                {"group":"watchers","partitions":[\
                {"topic":"orders","partition":0,"committedOffset":2,"highWaterMark":5,"lag":3},\
                {"topic":"orders","partition":1,"committedOffset":2,"highWaterMark":2,"lag":0},\
                {"topic":"shipments","partition":0,"committedOffset":1,"highWaterMark":3,"lag":2}]}""",
                lag.body(),
                "lag is the high-water mark less the offset committed: 5-2, 2-2 for a partition read to"
                        + " its end, and 3-1");
    }

    @Test
    void answersNotFoundForATopicTheBrokerDoesNotHold() throws Exception {
        int brokerPort = startBroker();
        fillTheBroker(brokerPort);
        startFacade(brokerPort);

        HttpResponse<String> response = get("/api/v1/topics/invoices");

        assertPlainError(response, 404, "no such topic");
    }

    @Test
    void answersNotFoundForAGroupThatHasCommittedNothing() throws Exception {
        int brokerPort = startBroker();
        fillTheBroker(brokerPort);
        startFacade(brokerPort);

        HttpResponse<String> response = get("/api/v1/groups/nobody/lag");

        // A commit is what creates a group, so the broker cannot tell a group with no commits from one
        // it has never heard of. The sentence claims only the part it knows.
        assertPlainError(response, 404, "group has no committed offsets: nobody");
    }

    @Test
    void answersBadRequestForANameTheProtocolWillNotCarry() throws Exception {
        int brokerPort = startBroker();
        startFacade(brokerPort);

        HttpResponse<String> response = get("/api/v1/topics/orders!");

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("[A-Za-z0-9._-]"),
                "the caller is told the rule its own name broke: " + response.body());
        assertNothingLeaked(response);
    }

    /**
     * With nothing listening on the broker's port, a connect can only end one way, so a 400 here is
     * the whole proof that none was attempted: the name was judged first, by the rule the broker would
     * have applied to it, and both endpoints judge it in the same place.
     */
    @Test
    void answersBadRequestForAnUnusableNameEvenWithNoBrokerToConnectTo() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        HttpResponse<String> topic = get("/api/v1/topics/bad!name");
        HttpResponse<String> group = get("/api/v1/groups/bad!name/lag");

        assertEquals(400, topic.statusCode(), "a connect was attempted before the name was judged: " + topic.body());
        assertEquals(400, group.statusCode(), "a connect was attempted before the name was judged: " + group.body());
        assertTrue(topic.body().startsWith("{\"error\":\"topic must be 1 to 200 characters"), topic.body());
        assertTrue(group.body().startsWith("{\"error\":\"groupId must be 1 to 200 characters"), group.body());
        assertNothingLeaked(topic);
        assertNothingLeaked(group);
    }

    @Test
    void answersServiceUnavailableWhenTheBrokerIsNotListening() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        HttpResponse<String> response = get("/api/v1/topics");

        assertPlainError(response, 503, "broker unreachable");
    }

    /**
     * A caller that will take nothing this facade produces is asking for something rather than
     * breaking something. Until this was mapped it fell through to the catch-all: 500, which is the
     * wrong story, and a stack trace per header, which is the disk somebody else gets to fill that the
     * 4xx paths were already fixed for.
     */
    @Test
    void answersNotAcceptableWithoutAStackWhenACallerWillTakeNoMediaTypeThisFacadeProduces() throws Exception {
        int brokerPort = startBroker();
        fillTheBroker(brokerPort);
        startFacade(brokerPort);

        HttpResponse<String> response;
        List<LogRecord> lines;
        try (CapturedLog captured = CapturedLog.of(ErrorResponses.class)) {
            response = get("/api/v1/topics", "application/xml");
            lines = captured.lines();
        }

        assertPlainError(response, 406, "request refused");
        assertTrue(contentTypeOf(response).startsWith("application/json"), contentTypeOf(response));
        assertEquals(List.of("FINE answering 406 request refused"), writtenDown(lines),
                "a header a caller chose is worth one quiet line and no stack");
    }

    /**
     * A broker that is down is a state rather than an event: every request that arrives while it holds
     * meets the same failure, so the answer a stranger can ask for a thousand times has to cost one
     * line. Three of them used to write some four hundred between them.
     */
    @Test
    void writesOneLineAndNoStackForEachOfABurstOfRequestsToABrokerThatIsNotListening() throws Exception {
        int brokerPort = aPortNothingIsListeningOn();
        startFacade(brokerPort);

        List<Integer> statuses = new ArrayList<>();
        List<LogRecord> lines;
        try (CapturedLog captured = CapturedLog.of(ErrorResponses.class)) {
            for (int request = 0; request < BURST_REQUEST_COUNT; request++) {
                statuses.add(get("/api/v1/topics").statusCode());
            }
            lines = captured.lines();
        }

        assertEquals(List.of(503, 503, 503, 503, 503), statuses);
        assertEquals(BURST_REQUEST_COUNT, lines.size(), "one line each, and no more: " + writtenDown(lines));
        assertEquals(List.of(), stacksIn(lines), "a stack was written for a broker a caller found down");
        assertTrue(lines.stream().allMatch(line -> line.getLevel().equals(Level.SEVERE)
                        && line.getMessage().startsWith("answering 503 broker unreachable: cannot connect to "
                                + LOOPBACK + ":" + brokerPort)),
                "each line still names the broker that could not be reached: " + writtenDown(lines));
    }

    /**
     * The one test in this build that waits on real time, and it waits only to bound it. A broker that
     * accepts a connection and then says nothing is the case a read timeout exists for, and the bound
     * asserted here is deliberately loose: what it rules out is the client library's thirty-second
     * default being what this facade waits, not any particular moment inside the five seconds it asks
     * for. Which bound actually fired is read off the line the facade wrote, where it is exact.
     */
    @Test
    void givesUpOnABrokerThatAcceptsAConnectionAndThenSaysNothing() throws Exception {
        try (ScriptedServer silentBroker = ScriptedServer.start("silent-broker", socket -> { })) {
            startFacade(silentBroker.port());

            HttpResponse<String> response;
            List<LogRecord> lines;
            long startedNanos = System.nanoTime();
            try (CapturedLog captured = CapturedLog.of(ErrorResponses.class)) {
                response = get("/api/v1/topics");
                lines = captured.lines();
            }
            long elapsedMillis = NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

            assertPlainError(response, 503, "broker unreachable");
            assertTrue(elapsedMillis < GIVING_UP_BOUND_MILLIS,
                    "a describe waited " + elapsedMillis + "ms, which is the client default rather than this"
                            + " facade's own bound");
            assertTrue(writtenDown(lines).stream().anyMatch(line -> line.contains("did not finish answering within "
                            + DESCRIBE_READ_TIMEOUT_MILLIS + "ms")),
                    "the bound that fired is the one sized for a describe: " + writtenDown(lines));
        }
    }

    /**
     * The cap on connections is only half a bound: sixty-four sockets that connect and say nothing
     * hold every place under it until Tomcat reclaims them, and Tomcat's own default for that is a
     * minute. This asserts the pin where the container actually read it, rather than in the properties
     * file where it is written.
     */
    @Test
    void boundsHowLongAConnectionMayHoldAPlaceUnderTheCapWithoutAskingAnything() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        AbstractProtocol<?> protocol = (AbstractProtocol<?>) tomcatOf(facade).getConnector().getProtocolHandler();

        assertEquals(CONNECTION_TIMEOUT_MILLIS, protocol.getConnectionTimeout(),
                "a silent connection would hold its place for Tomcat's default minute");
    }

    @Test
    void answersNotFoundWithoutDetailForAPathItDoesNotServe() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        HttpResponse<String> response = get("/nothing-is-served-here");

        // Spring's own error page never gets the chance: a path nobody serves comes back in the same
        // one-field shape as every other failure.
        assertPlainError(response, 404, "no such endpoint");
    }

    /**
     * The facade's own answer to an unmapped path is quiet, and the framework's is not: the
     * dispatcher writes one WARN naming the path before this facade is asked anything, which is log
     * volume a stranger with a list of paths decides. The pin in {@code application.properties} is
     * asserted through the predicate the dispatcher itself consults before writing that line.
     */
    @Test
    void answersAPathNobodyServesWithoutTheFrameworkWritingAWarningPerRequest() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        HttpResponse<String> response = get("/nothing-is-served-here");

        assertEquals(404, response.statusCode(), response.body());
        assertFalse(LoggerFactory.getLogger(DispatcherServlet.PAGE_NOT_FOUND_LOG_CATEGORY).isWarnEnabled(),
                "the dispatcher writes one warning per unmapped path, and a caller picks how many");
    }

    /**
     * {@code /error} is where the servlet container forwards, and it is reachable from outside like any
     * other path. Both answers are the facade's own shape: the JSON one because that is the shape, and
     * the HTML one because a caller's Accept header does not get to choose a second one.
     */
    @Test
    void answersTheContainersErrorPathInItsOwnShapeWhateverTheCallerWillAccept() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        HttpResponse<String> asJson = get("/error", "application/json");
        HttpResponse<String> asHtml = get("/error", "text/html");

        // Nothing forwarded, so there is no original status to resolve and no failure to report: what
        // the caller did was ask for a path this facade does not serve.
        assertPlainError(asJson, 404, "no such endpoint");
        assertPlainError(asHtml, 404, "no such endpoint");
        assertTrue(contentTypeOf(asHtml).startsWith("application/json"), contentTypeOf(asHtml));
        assertFalse(asHtml.body().contains("<"), "an HTML page reached the caller: " + asHtml.body());
    }

    /**
     * Tomcat refuses these two prefixes itself, before the dispatcher is asked anything, and forwards
     * the 404 it chose. Without a controller of this facade's own on that forward, the answer is Spring
     * Boot's default error body — a different shape, carrying a timestamp and the caller's own path.
     */
    @Test
    void answersAPathTheContainerRefusesInTheSameShapeAsEveryOtherFailure() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        HttpResponse<String> underWebInf = get("/WEB-INF/x");
        HttpResponse<String> underMetaInf = get("/META-INF/x");

        assertPlainError(underWebInf, 404, "no such endpoint");
        assertPlainError(underMetaInf, 404, "no such endpoint");
    }

    /**
     * The three answers a caller can get from three different places — a controller, this facade's
     * advice, and the container's own forward — all leave through the same socket, so all three carry
     * the headers that say what these bodies are not.
     */
    @Test
    void carriesItsSecurityHeadersOnAnAnswerOnAFailureAndOnAPathTheContainerRefuses() throws Exception {
        int brokerPort = startBroker();
        startFacade(brokerPort);

        HttpResponse<String> served = get("/api/v1/topics");
        HttpResponse<String> refusedHere = get("/nothing-is-served-here");
        HttpResponse<String> refusedByTheContainer = get("/WEB-INF/x");

        assertEquals(200, served.statusCode(), served.body());
        assertSaysWhatTheBodyIsNot(served);
        assertPlainError(refusedHere, 404, "no such endpoint");
        assertSaysWhatTheBodyIsNot(refusedHere);
        assertPlainError(refusedByTheContainer, 404, "no such endpoint");
        assertSaysWhatTheBodyIsNot(refusedByTheContainer);
    }

    /**
     * A request target holding a broken percent-escape is refused while Tomcat is still parsing bytes,
     * so no servlet, no filter, and no advice of this facade's ever sees it. What it must not get back
     * is Tomcat's own HTML report, which names the server and its version.
     */
    @Test
    void answersARequestTomcatRefusesWithAStatusAndAnEmptyBodyRatherThanAPage() throws Exception {
        startFacade(aPortNothingIsListeningOn());

        String answer = rawGet("/%zz");

        assertTrue(answer.startsWith("HTTP/1.1 400"), answer);
        assertFalse(answer.contains("<"), "a page reached the caller: " + answer);
        assertFalse(answer.toLowerCase(Locale.ROOT).contains("tomcat"),
                "the server named itself to the caller: " + answer);
        assertEquals("", entityOf(answer), "a request the container refused is answered with no body at all");
    }

    /**
     * Starts a broker in a process of its own and waits for the ready file that says which port it
     * bound.
     *
     * @return that port
     */
    private int startBroker() throws IOException {
        Path readyFilePath = workingDirectory.resolve("shrike.ready");
        JavaProcess broker = JavaProcess.start("broker", BrokerProcessMain.class,
                workingDirectory.resolve("broker.out"),
                List.of(dataDirectory().toString(), readyFilePath.toString()));
        started.add(broker);
        return Await.brokerPort(readyFilePath, broker);
    }

    /**
     * Starts the facade in this JVM, on a port the operating system chooses, pointed at one broker.
     *
     * @param brokerPort the port the facade should ask its questions on
     */
    private void startFacade(int brokerPort) {
        facade = SpringApplication.run(ShrikeAdminApplication.class,
                "--server.port=0",
                "--shrike.broker.host=" + LOOPBACK,
                "--shrike.broker.port=" + brokerPort);
        facadePort = ((WebServerApplicationContext) facade).getWebServer().getPort();
    }

    /**
     * Puts a known amount of everything on the broker: two topics, records in three of their five
     * partitions, and a group that has committed part of the way through one of them, all of the way
     * through another, and one record into a third.
     *
     * @param brokerPort where that broker is listening
     */
    private static void fillTheBroker(int brokerPort) {
        ClientConfig config = ClientConfig.defaults(LOOPBACK, brokerPort);

        try (ShrikeTopics topics = ShrikeTopics.open(config)) {
            topics.create(ORDERS, ORDERS_PARTITION_COUNT);
            topics.create(SHIPMENTS, SHIPMENTS_PARTITION_COUNT);
        }
        try (ShrikeProducer producer = ShrikeProducer.open(config)) {
            produce(producer, ORDERS, 0, 5);
            produce(producer, ORDERS, 1, 2);
            produce(producer, SHIPMENTS, 0, 3);
        }
        try (ShrikeConsumer consumer = ShrikeConsumer.open(config)) {
            consumer.commitOffset(GROUP, ORDERS, 0, 2);
            consumer.commitOffset(GROUP, ORDERS, 1, 2);
            consumer.commitOffset(GROUP, SHIPMENTS, 0, 1);
        }
    }

    private static void produce(ShrikeProducer producer, String topic, int partition, int recordCount) {
        for (int index = 0; index < recordCount; index++) {
            producer.send(topic, partition, null, (topic + "-" + partition + "-" + index).getBytes(UTF_8));
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":" + facadePort + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    /**
     * @param path   the path to ask for
     * @param accept the one media type the caller says it will take
     */
    private HttpResponse<String> get(String path, String accept) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ":" + facadePort + path))
                .header("Accept", accept)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    }

    /**
     * Sends one request line as bytes and reads the whole answer back as bytes. The JDK's HTTP client
     * will not build a URI holding a broken escape, and that is the point: a request target this server
     * has to refuse before it parses one cannot be sent through a client that validates it first.
     *
     * @param requestTarget what goes between the method and the version, unvalidated
     * @return the answer, status line and headers and body together
     */
    private String rawGet(String requestTarget) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), facadePort)) {
            socket.getOutputStream().write(("GET " + requestTarget + " HTTP/1.1\r\nHost: " + LOOPBACK
                    + "\r\nConnection: close\r\n\r\n").getBytes(UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), UTF_8);
        }
    }

    /**
     * @param answer a whole HTTP answer as it arrived
     * @return what the answer carries as content. An answer with no content length declared is framed
     *         with chunked transfer encoding, whose whole of "nothing" is one terminating chunk, so
     *         that marker is removed here and an empty body reads as an empty string either way
     */
    private static String entityOf(String answer) {
        int endOfHeaders = answer.indexOf("\r\n\r\n");
        String body = endOfHeaders < 0 ? "" : answer.substring(endOfHeaders + 4);
        return CHUNKED_END_OF_BODY.equals(body) ? "" : body;
    }

    /**
     * @param response  an answer that is not a 200
     * @param status    the status it should carry
     * @param message   the whole of the sentence its body should hold
     */
    private void assertPlainError(HttpResponse<String> response, int status, String message) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals("{\"error\":\"" + message + "\"}", response.body());
        assertNothingLeaked(response);
    }

    /**
     * The rule every answer keeps, whatever its status: it says the bytes are to be taken as the type
     * they were declared as, and that a browser handed them anyway may load nothing and frame nothing.
     */
    private void assertSaysWhatTheBodyIsNot(HttpResponse<String> response) {
        assertEquals(SecurityHeaders.NOSNIFF, headerOf(response, SecurityHeaders.NO_SNIFFING),
                "a browser is left to guess this body's type from its bytes");
        assertEquals(SecurityHeaders.LOAD_NOTHING_AND_BE_FRAMED_BY_NOBODY,
                headerOf(response, SecurityHeaders.CONTENT_SECURITY_POLICY));
    }

    private static String headerOf(HttpResponse<String> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    /**
     * The rule every failing answer keeps: a caller learns what went wrong and nothing about the
     * machine it went wrong on.
     */
    private void assertNothingLeaked(HttpResponse<String> response) {
        String body = response.body();
        assertFalse(body.contains("Exception"), "an exception class reached the caller: " + body);
        assertFalse(body.contains("\tat "), "a stack trace reached the caller: " + body);
        assertFalse(body.contains("/Users/"), "a path on this machine reached the caller: " + body);
        assertFalse(body.contains(workingDirectory.toString()),
                "the broker's data directory reached the caller: " + body);
        assertFalse(body.contains("timestamp"),
                "the framework's own error body reached the caller: " + body);
    }

    private static String contentTypeOf(HttpResponse<String> response) {
        return response.headers().firstValue("content-type").orElse("");
    }

    /**
     * @param facade a facade this test started
     * @return the embedded server behind it, for the settings that are the container's rather than
     *         this facade's own
     */
    private static Tomcat tomcatOf(ConfigurableApplicationContext facade) {
        return ((TomcatWebServer) ((WebServerApplicationContext) facade).getWebServer()).getTomcat();
    }

    /**
     * @param lines every line a class wrote while a test watched
     * @return each of them as its level and its sentence, which is what an operator would have read
     */
    private static List<String> writtenDown(List<LogRecord> lines) {
        return lines.stream().map(line -> line.getLevel().getName() + " " + line.getMessage()).toList();
    }

    /**
     * @param lines every line a class wrote while a test watched
     * @return the ones that carry a throwable, which is what gets printed as a stack trace. A status a
     *         caller can ask for repeatedly must produce none of these
     */
    private static List<String> stacksIn(List<LogRecord> lines) {
        return lines.stream()
                .filter(line -> line.getThrown() != null)
                .map(line -> line.getMessage() + " <- " + line.getThrown())
                .toList();
    }

    private Path dataDirectory() {
        return workingDirectory.resolve("data");
    }

    /**
     * @param topic     a topic the broker holds
     * @param partition one of its partitions
     * @return every byte that partition's segment files occupy, read from the filesystem rather than
     *         from the broker, so that the number in the answer is checked against the disk itself
     */
    private long bytesOnDisk(String topic, int partition) {
        Path partitionDirectory = dataDirectory().resolve(topic + "-" + partition);
        try (Stream<Path> files = Files.list(partitionDirectory)) {
            long totalBytes = 0L;
            for (Path file : files.toList()) {
                totalBytes += Files.size(file);
            }
            return totalBytes;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot measure " + partitionDirectory, e);
        }
    }

    /**
     * @return a loopback port that was bound and then closed, so that connecting to it is refused
     *         rather than left hanging
     */
    private static int aPortNothingIsListeningOn() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}
