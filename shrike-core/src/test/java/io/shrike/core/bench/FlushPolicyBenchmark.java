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
 * What {@code flush.mode} costs one produce's worth of appends: {@code batchRecords} calls to
 * {@link SegmentedLog#append} under {@link FlushMode#PER_RECORD}, where every record is forced before
 * the call that appended it returns, against the same appends under {@link FlushMode#INTERVAL} at its
 * defaults, where the append that crosses {@code flush.interval.bytes} is the one that forces.
 *
 * <p><strong>One invocation is one produce, and {@code batchRecords} says how big a produce.</strong>
 * {@code Partition.produce} appends a request's records one at a time under one lock, so a produce
 * carrying N records makes N appends and — under {@code per-record} — N forces. At
 * {@code batchRecords=1} this measures a single append, which is the figure a per-record cost is read
 * from; at {@code batchRecords=100} it measures what one produce of a hundred records costs, which is
 * the figure an operator sizing a client's batch actually needs. The lock and the socket are outside
 * both: a produce is acknowledged once the appends have returned, so the appends are the whole of what
 * a flush mode changes.
 *
 * <p>The log is opened with {@link LogConfig#defaults()} apart from the mode — 128 MiB segments, an
 * index entry every 4 KiB, retention off — so a segment roll and the force that seals it fall inside
 * the measurement exactly as they fall inside a running broker's.
 *
 * <p><strong>Only the {@code interval} arms reach a roll.</strong> At the rates the two modes run at,
 * a {@code per-record} trial appends a few thousand records over its warmup and measured seconds —
 * some hundreds of kibibytes of frames — and never comes near the 128 MiB {@code segment.bytes}, so it
 * rolls no segment at all. An {@code interval} trial appends millions in the same seconds, hundreds of
 * mebibytes, and rolls and seals a segment about every 128 MiB while it does. The cost of rolling and
 * sealing is therefore charged to the {@code interval} arms alone, and it lands in their tail.
 *
 * <p><strong>What is not measured, in {@code interval} mode, is the time bound.</strong> The
 * {@code shrike-flush} thread is a broker's, not a log's, and it forces from a thread of its own; the
 * bound that shows up here is the volume one, because that is the bound an append itself can cross.
 *
 * <p>Two result kinds are produced from the same call. {@link Mode#Throughput} answers how many
 * produces a second one writer sustains, and {@link Mode#SampleTime} is what a percentile can be read
 * off — a mean cannot be turned into a p99, so the tail needs per-invocation samples. Every sampled
 * number here is <em>closed-loop service time</em>: the harness issues the next produce only once the
 * previous one has returned, so nothing queues behind anything, and a percentile from this benchmark
 * understates what a client would see under an arrival rate it does not control.
 *
 * <p><strong>A {@code per-record} tail wants more measured seconds than this class asks for.</strong>
 * The annotations below are what the whole suite runs at; the sampled {@code per-record} arm is run a
 * second time, deeper, from the {@code per-record-tail} execution of the {@code bench} profile, because
 * at roughly four milliseconds an append the iterations here can only produce a couple of thousand
 * samples and a p99 read off that few is a thin number. What that execution changes is the iteration
 * count and their length, which JMH records in the result file it writes.
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

    /**
     * How many records one invocation appends, which is how many records the produce it stands for
     * carries. One is the per-record figure; a hundred is a batch a client library would send without
     * anybody thinking it large, and under {@code per-record} it is a hundred forces.
     */
    @Param({"1", "100"})
    public int batchRecords;

    private Path dataDirectory;
    private SegmentedLog log;
    private ProducedRecord record;

    /**
     * Opens a log of its own for this trial, so a measurement never starts on a directory another one
     * left behind. Fresh per trial and not per iteration: the warmup and measurement iterations of one
     * trial all append to this same log, so under {@code interval} it goes on growing and rolling
     * across them.
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
     * @return the offset the last record of the produce was appended at, returned so that nothing
     *         about the appends can be eliminated as dead code
     */
    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    public long appendOneProduceThroughput() {
        return appendOneProduce();
    }

    /**
     * The same produce, sampled per invocation so that the tail can be read rather than averaged. Every
     * percentile it produces is closed-loop service time.
     *
     * @return the offset the last record of the produce was appended at
     */
    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public long appendOneProduceServiceTime() {
        return appendOneProduce();
    }

    /**
     * Appends the records of one produce, one at a time and in order, which is what
     * {@code Partition.produce} does with the records of a produce request.
     *
     * @return the offset the last of them was appended at
     */
    private long appendOneProduce() {
        long lastOffset = -1L;
        for (int appended = 0; appended < batchRecords; appended++) {
            lastOffset = log.append(record);
        }
        return lastOffset;
    }
}
