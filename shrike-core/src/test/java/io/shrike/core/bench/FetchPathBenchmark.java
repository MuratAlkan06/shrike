package io.shrike.core.bench;

import io.shrike.core.log.ByteChannels;
import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.RecordRange;
import io.shrike.core.log.SegmentedLog;
import io.shrike.core.log.WriteProgress;
import io.shrike.core.time.SystemTimeSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What sending a fetch's records out of the segment file costs against reading them into memory
 * first: {@link SegmentedLog#openRange} plus {@link RecordRange#transferTo} against
 * {@link SegmentedLog#readRange} plus {@link ByteChannels#writeFully}.
 *
 * <p><strong>The two methods are the two paths {@code fetch.zero.copy} selects, and nothing else.</strong>
 * {@code Partition.readable} is a two-line switch between exactly these calls, so the A/B here is the
 * setting itself rather than a variant written for a benchmark. Both are asked for the same range of
 * the same pre-built log on every invocation — one fixed fetch offset, one fixed byte cap, no appends
 * in between — and the setup refuses to run if the two disagree about how many bytes that range is,
 * because a comparison of two byte counts is not a comparison of two paths.
 *
 * <p><strong>The sink is a connected pair of loopback {@link SocketChannel}s</strong>, with a thread
 * named {@code shrike-bench-drain} reading the other end into one reused buffer and discarding it.
 * That is a deliberate choice and it decides what these numbers mean. A transfer only differs from a
 * read-then-write when the destination is a socket: {@code FileChannel.transferTo} reaches the
 * kernel's own file-to-socket path there, and to an arbitrary {@code WritableByteChannel} the JDK
 * falls back to the copy the other path already makes, which would have made the A/B a measurement of
 * nothing. So what is measured is the whole of what a broker does with a fetch's records — locate the
 * range, then get its bytes onto a connection — and it includes loopback TCP and whatever the drainer
 * costs. It is not a measurement of the file read alone, and neither number is a measurement of a
 * network. Both paths pay the same socket, which is what leaves the difference between them.
 *
 * <p><strong>Two further methods price the floor under each of those two paths, and neither is a fetch
 * path.</strong> {@link #writeRangeToTheSinkAlone} writes the same bytes out of the same heap array
 * into the same loopback pair with no log behind it, which is the floor under the buffered row: what
 * that row pays for the socket once the read and the allocation are taken away.
 * {@link #transferWarmFileToTheSinkAlone} transfers the same bytes out of an already-warm file into the
 * same pair with no log behind it — no range located, no descriptor opened per invocation — which is
 * the floor under the transfer row, and it is a different number from the other floor because
 * {@code transferTo} out of a file and {@code write} out of a heap buffer are different system calls
 * doing different work. Pricing one shared sink and subtracting it from both rows would have charged
 * the transfer row for a copy it never makes; the two floors are measured separately for that reason,
 * and each row is read against its own.
 *
 * <p><strong>The transfer path opens a file descriptor per fetch and the buffered path does not.</strong>
 * {@link SegmentedLog#openRange} opens a second descriptor on the segment file and the
 * {@link RecordRange} closes it once the range has been sent, while {@link SegmentedLog#readRange}
 * reads through the channel the segment already holds. That asymmetry is the broker's rather than this
 * benchmark's — {@code LogSegment.openRange} is the same call a served fetch makes, and the second
 * descriptor is what lets a range still be sent after retention has deleted the segment it is in — so
 * the open and the close are inside the transfer number rather than taken out of it.
 *
 * <p><strong>The buffered path allocates a mebibyte per fetch and the transfer path allocates
 * nothing.</strong> {@link SegmentedLog#readRange} answers with a {@code byte[]} sized to the range, so
 * every invocation of {@link #serveRangeByBuffer} allocates a fresh 1 048 478-byte array — larger than
 * half a G1 region at any heap size this machine defaults to, which makes it a humongous allocation
 * rather than one served from a thread-local buffer. That is the broker's own behaviour and not this
 * benchmark's, so it is inside the buffered number; the {@code gc} profiler is what says how much of
 * that number it is, and the {@code fetch-allocation} execution of the {@code bench} profile is what
 * runs it.
 *
 * <p>The response header is outside both calls, because it is byte for byte the same in both paths and
 * is written the same way in both. What varies is only how the records block reaches the wire.
 *
 * <p>One thread, because a partition serves its reads under one lock and a second reading thread here
 * would be measuring the lock rather than the two paths.
 */
@State(Scope.Thread)
@Threads(1)
@Fork(2)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class FetchPathBenchmark {

    private static final String TOPIC = "bench";
    private static final int PARTITION = 0;

    /** The payload of every stored record, which frames to 1058 bytes on disk. */
    private static final int VALUE_BYTES = 1024;

    /** Enough records that the range below is filled by the cap rather than by the high-water mark. */
    private static final int RECORD_COUNT = 2048;

    /** The {@code maxBytes} of every fetch here: one mebibyte, rounded down to whole frames by the log. */
    private static final int RANGE_MAX_BYTES = 1024 * 1024;

    /** Where every fetch here starts, so that each invocation serves the same bytes as the last. */
    private static final long FETCH_OFFSET = 0L;

    /** What the drainer reads into, once per read, for the life of a trial. */
    private static final int DRAIN_BUFFER_BYTES = 1024 * 1024;

    /** How long a trial waits for the drainer to end after its socket has been closed. */
    private static final long DRAIN_JOIN_MILLIS = 5_000L;

    /** What the file holding the range's bytes with no log around them is called inside the trial's dir. */
    private static final String WARM_FILE_NAME = "warm-file.bytes";

    private Path dataDirectory;
    private SegmentedLog log;
    private long highWaterMark;

    /**
     * The bytes the range covers, read once at trial setup so that {@link #writeRangeToTheSinkAlone}
     * sends exactly what the two fetch paths send with no log in the call that sends it.
     */
    private byte[] recordsBlock;

    /**
     * A plain file holding those same bytes and nothing else, so that
     * {@link #transferWarmFileToTheSinkAlone} transfers exactly what the transfer path transfers out of
     * a file the log knows nothing about. It is not a segment: it has no index, no base offset, and no
     * frames as far as anything here is concerned — it is a mebibyte on disk.
     */
    private Path warmFile;

    /**
     * One descriptor on {@link #warmFile}, opened at trial setup and read through by every invocation.
     * It is deliberately not opened per invocation: the open is what the transfer <em>row</em> pays and
     * this is the floor under that row, so the floor is the transfer alone.
     */
    private FileChannel warmFileChannel;

    private ServerSocketChannel listener;

    /** The end a fetch is written to: the broker's side of a connection. */
    private SocketChannel brokerEnd;

    /** The end the drainer reads: the client's side of the same connection. */
    private SocketChannel clientEnd;

    private Thread drainer;

    /**
     * What the drainer failed with, so that a trial fails with it rather than measuring a socket that
     * stopped being read.
     */
    // guarded by: written once by shrike-bench-drain before it ends, read by the benchmark thread
    // after it has joined that thread, which is what publishes it
    private IOException drainFailure;

    /**
     * Builds the log, connects the loopback pair, and starts the drainer.
     *
     * @throws IOException if the directory, the log, or the sockets cannot be opened
     */
    @Setup(Level.Trial)
    public void openTheLogAndTheSink() throws IOException {
        dataDirectory = TemporaryDataDirectory.create("shrike-bench-fetch-");
        log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, new SystemTimeSource(), LogConfig.defaults());

        byte[] value = new byte[VALUE_BYTES];
        Arrays.fill(value, (byte) 'x');
        for (int record = 0; record < RECORD_COUNT; record++) {
            log.append(new ProducedRecord(null, value));
        }
        highWaterMark = log.nextOffset();
        requireBothPathsCoverTheSameBytes();
        recordsBlock = log.readRange(FETCH_OFFSET, highWaterMark, RANGE_MAX_BYTES);
        openTheWarmFile();

        listener = ServerSocketChannel.open();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        brokerEnd = SocketChannel.open(listener.getLocalAddress());
        clientEnd = listener.accept();

        drainer = new Thread(this::drain, "shrike-bench-drain");
        drainer.start();
    }

    /**
     * Ends the drainer by closing the socket it is reading, then closes everything else and removes
     * the log.
     *
     * @throws IOException          if the drainer failed, or something cannot be closed or removed
     * @throws InterruptedException if the wait for the drainer is interrupted
     */
    @TearDown(Level.Trial)
    public void closeTheSinkAndTheLog() throws IOException, InterruptedException {
        brokerEnd.close();
        drainer.join(DRAIN_JOIN_MILLIS);
        if (drainer.isAlive()) {
            throw new IllegalStateException("shrike-bench-drain did not end within " + DRAIN_JOIN_MILLIS
                    + "ms of its socket closing");
        }
        clientEnd.close();
        listener.close();
        warmFileChannel.close();
        log.close();
        TemporaryDataDirectory.delete(dataDirectory);

        if (drainFailure != null) {
            throw drainFailure;
        }
    }

    /**
     * Sends the range straight out of the segment file, which is what {@code fetch.zero.copy=true}
     * does.
     *
     * @return how many bytes the range covered, returned so the work cannot be eliminated
     * @throws IOException if the file or the socket fails
     */
    @Benchmark
    public long serveRangeByTransfer() throws IOException {
        try (RecordRange range = log.openRange(FETCH_OFFSET, highWaterMark, RANGE_MAX_BYTES)) {
            range.transferTo(brokerEnd);
            return range.lengthBytes();
        }
    }

    /**
     * Reads the range into a buffer and writes that, which is what {@code fetch.zero.copy=false} does.
     *
     * @return how many bytes the range covered, returned so the work cannot be eliminated
     * @throws IOException if the file or the socket fails
     */
    @Benchmark
    public long serveRangeByBuffer() throws IOException {
        byte[] records = log.readRange(FETCH_OFFSET, highWaterMark, RANGE_MAX_BYTES);
        ByteChannels.writeFully(brokerEnd, ByteBuffer.wrap(records));
        return records.length;
    }

    /**
     * Writes the same bytes into the same sink with nothing behind the write: no range located, no
     * file read, no descriptor opened. It is not one of the two paths above and it is not a fetch —
     * it is what both of those pay for the socket, measured rather than assumed, so that a reader can
     * tell whether either row is bound by the sink and can take the sink off both before comparing
     * them.
     *
     * <p>The buffer is a heap one wrapping the same array on every invocation, which is what
     * {@link #serveRangeByBuffer} hands {@code writeFully} after its read: the array is reused here
     * because allocating and filling it is the log's cost rather than the socket's.
     *
     * @return how many bytes were written, returned so the work cannot be eliminated
     * @throws IOException if the socket fails
     */
    @Benchmark
    public long writeRangeToTheSinkAlone() throws IOException {
        ByteChannels.writeFully(brokerEnd, ByteBuffer.wrap(recordsBlock));
        return recordsBlock.length;
    }

    /**
     * Transfers the same bytes out of a warm file into the same sink with no log behind the transfer:
     * no range located, no segment file opened, no frame boundary found. It is not one of the two paths
     * above and it is not a fetch — it is the floor under {@link #serveRangeByTransfer}, so that what
     * that row costs above a bare file-to-socket transfer is a measurement rather than the same sink
     * cost subtracted from both rows.
     *
     * <p>The ask is capped at {@link WriteProgress#MAX_BYTES_BETWEEN_REPORTS} in the same loop shape
     * {@link RecordRange#transferTo} uses, which for a range this size is one call either way, so the
     * floor and the row it sits under make the same system call the same number of times.
     *
     * @return how many bytes were transferred, returned so the work cannot be eliminated
     * @throws IOException if the file or the socket fails
     */
    @Benchmark
    public long transferWarmFileToTheSinkAlone() throws IOException {
        long positionBytes = 0L;
        long remainingBytes = recordsBlock.length;
        while (remainingBytes > 0) {
            long askBytes = Math.min(remainingBytes, WriteProgress.MAX_BYTES_BETWEEN_REPORTS);
            long transferredBytes = warmFileChannel.transferTo(positionBytes, askBytes, brokerEnd);
            if (transferredBytes <= 0) {
                throw new IOException("the warm file " + warmFile + " gave " + positionBytes + " of the "
                        + recordsBlock.length + " bytes it holds");
            }
            positionBytes += transferredBytes;
            remainingBytes -= transferredBytes;
        }
        return positionBytes;
    }

    /**
     * Writes the range's bytes into a file of their own, opens one descriptor on it, and reads the whole
     * of it once so that the first measured transfer meets the same page cache every later one does.
     *
     * @throws IOException if the file cannot be written, opened, or read
     */
    private void openTheWarmFile() throws IOException {
        warmFile = dataDirectory.resolve(WARM_FILE_NAME);
        Files.write(warmFile, recordsBlock);
        warmFileChannel = FileChannel.open(warmFile, StandardOpenOption.READ);

        ByteBuffer warming = ByteBuffer.allocateDirect(recordsBlock.length);
        while (warming.hasRemaining() && warmFileChannel.read(warming) >= 0) {
            // Reading it whole is the warming; where the bytes went is of no interest.
        }
        if (warming.hasRemaining()) {
            throw new IllegalStateException("the warm file holds " + (recordsBlock.length - warming.remaining())
                    + " of the " + recordsBlock.length + " bytes the range covers");
        }
    }

    /**
     * Reads whatever arrives until the other end is closed. A benchmark that writes into a socket
     * nobody reads measures a socket buffer filling up, so this thread exists to keep the one thing
     * both paths share out of the difference between them.
     */
    private void drain() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(DRAIN_BUFFER_BYTES);
        try {
            while (clientEnd.read(buffer) >= 0) {
                buffer.clear();
            }
        } catch (IOException e) {
            // Kept rather than thrown: this thread has nobody to throw to, and a trial that measured
            // a socket whose reader had died would report a number rather than a failure. The
            // teardown joins this thread and then raises it.
            drainFailure = e;
        }
    }

    /**
     * Fails the trial unless the two paths agree about the range, so that a comparison of two numbers
     * is a comparison of two ways of sending the same bytes.
     */
    private void requireBothPathsCoverTheSameBytes() {
        int copiedBytes = log.readRange(FETCH_OFFSET, highWaterMark, RANGE_MAX_BYTES).length;
        try (RecordRange range = log.openRange(FETCH_OFFSET, highWaterMark, RANGE_MAX_BYTES)) {
            if (range.lengthBytes() != copiedBytes) {
                throw new IllegalStateException("the two fetch paths located different ranges: "
                        + range.lengthBytes() + " bytes opened against " + copiedBytes + " bytes read");
            }
        }
        if (copiedBytes < RANGE_MAX_BYTES - VALUE_BYTES) {
            throw new IllegalStateException("the range is bounded by the high-water mark rather than by"
                    + " maxBytes: " + copiedBytes + " bytes of a " + RANGE_MAX_BYTES + " byte cap");
        }
    }
}
