package io.shrike.core.bench;

import io.shrike.core.log.FlushMode;
import io.shrike.core.log.LogConfig;
import io.shrike.core.log.ProducedRecord;
import io.shrike.core.log.SegmentedLog;
import io.shrike.core.time.SystemTimeSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What {@code flush.mode} costs an append: one {@link SegmentedLog#append} under
 * {@link FlushMode#PER_RECORD}, where the record is forced before the call returns, against the same
 * append under {@link FlushMode#INTERVAL} at its defaults, where the append that crosses
 * {@code flush.interval.bytes} is the one that forces.
 *
 * <p><strong>What is measured is one call to {@code append} and nothing around it.</strong> There is
 * no socket, no protocol, and no broker here: a produce is acknowledged once {@code append} returns,
 * so the append is the whole of what a flush mode changes. The log is opened with
 * {@link LogConfig#defaults()} apart from the mode — 128 MiB segments, an index entry every 4 KiB,
 * retention off — so a segment roll and the force that seals it fall inside the measurement exactly
 * as they fall inside a running broker's.
 *
 * <p><strong>What is not measured, in {@code interval} mode, is the time bound.</strong> The
 * {@code shrike-flush} thread is a broker's, not a log's, and it forces from a thread of its own; the
 * bound that shows up here is the volume one, because that is the bound an append itself can cross.
 *
 * <p>Two result kinds are produced from the same call. {@link Mode#Throughput} answers how many
 * appends a second one writer sustains, and {@link Mode#SampleTime} is what a percentile can be read
 * off — a mean cannot be turned into a p99, so the tail needs per-invocation samples. Every sampled
 * number here is <em>closed-loop service time</em>: the harness issues the next append only once the
 * previous one has returned, so nothing queues behind anything, and a percentile from this benchmark
 * understates what a client would see under an arrival rate it does not control.
 *
 * <p>One thread, because a partition has one writer by construction and a second appending thread
 * would be measuring the partition lock rather than the flush policy.
 */
@State(Scope.Thread)
@Threads(1)
@Fork(2)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
public class FlushPolicyBenchmark {

    private static final String TOPIC = "bench";
    private static final int PARTITION = 0;

    /** The payload of every appended record, which frames to 162 bytes on disk. */
    private static final int VALUE_BYTES = 128;

    /** Which mode the log under this trial is opened in; everything else is {@link LogConfig#defaults()}. */
    @Param({"PER_RECORD", "INTERVAL"})
    public FlushMode flushMode;

    private Path dataDirectory;
    private SegmentedLog log;
    private ProducedRecord record;

    /**
     * Opens a log of its own for this trial, so a measurement never starts on a directory another one
     * left behind.
     *
     * @throws IOException if the directory cannot be created
     */
    @Setup(Level.Trial)
    public void openTheLog() throws IOException {
        dataDirectory = TemporaryDataDirectory.create("shrike-bench-flush-");
        LogConfig config = new LogConfig(LogConfig.DEFAULT_MAX_RECORD_BYTES, LogConfig.DEFAULT_SEGMENT_BYTES,
                LogConfig.DEFAULT_INDEX_INTERVAL_BYTES, LogConfig.DEFAULT_RETENTION_MS,
                LogConfig.DEFAULT_RETENTION_BYTES, flushMode, LogConfig.DEFAULT_FLUSH_INTERVAL_MS,
                LogConfig.DEFAULT_FLUSH_INTERVAL_BYTES);
        log = SegmentedLog.open(dataDirectory, TOPIC, PARTITION, new SystemTimeSource(), config);

        byte[] value = new byte[VALUE_BYTES];
        Arrays.fill(value, (byte) 'x');
        record = new ProducedRecord(null, value);
    }

    /**
     * Closes the log and removes everything this trial appended.
     *
     * @throws IOException if the directory cannot be removed
     */
    @TearDown(Level.Trial)
    public void closeTheLog() throws IOException {
        log.close();
        TemporaryDataDirectory.delete(dataDirectory);
    }

    /**
     * @return the offset the record was appended at, returned so that nothing about the append can be
     *         eliminated as dead code
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long appendRecordThroughput() {
        return log.append(record);
    }

    /**
     * The same append, sampled per invocation so that the tail can be read rather than averaged. Every
     * percentile it produces is closed-loop service time.
     *
     * @return the offset the record was appended at
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public long appendRecordServiceTime() {
        return log.append(record);
    }
}
