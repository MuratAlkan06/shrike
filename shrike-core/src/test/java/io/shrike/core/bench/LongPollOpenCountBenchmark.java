package io.shrike.core.bench;

import io.shrike.core.net.LongPollFetchProbe;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.AuxCounters;
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
 * How many segment files a long-poll fetch opens: one, however many appends wake it before it can be
 * answered.
 *
 * <p><strong>What this publishes is a count and not a time.</strong> The score JMH prints for it is the
 * rate at which this harness can drive a fetch through {@code wakeups} thread handoffs, which is a
 * property of two threads passing a lock back and forth and says nothing about what a broker serves.
 * The numbers worth reading are the counters beside it: {@code segmentFileOpens},
 * {@code fetchWakeups}, and {@code longPollFetches}, all three of which JMH writes into the same result
 * file as the score, so that "one open per fetch" is a ratio of two committed numbers rather than an
 * assertion about them. Nothing here is a latency claim, and the rate is deliberately not published.
 *
 * <p><strong>What the count says.</strong> A fetch that finds too few bytes goes on holding the range it
 * located and asks again through that descriptor, so the {@code open(2)} the first wakeup with records
 * pays is the only one there is. A fetch woken N times therefore locates N ranges through one open,
 * where a fetch that let go of its range each time it waited would have opened the file once per
 * wakeup. The counter counts opens; the wakeups beside it are what says how many opens were not made.
 *
 * <p>The scenario is driven by {@link LongPollFetchProbe}, which lives in {@code io.shrike.core.net}
 * because the seam that counts an open is package-private there — as is the partition it counts for.
 * No seam was added for this benchmark: it counts through the one Slice 1.5 already added and
 * {@code PartitionFetchDescriptorTest} already asserts on.
 *
 * <p>One thread, because the count is per fetch and a second fetching thread would be measuring the
 * partition lock instead.
 */
@State(Scope.Thread)
@Threads(1)
@Fork(2)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class LongPollOpenCountBenchmark {

    /**
     * How many times the fetch under measurement is woken before it can be answered. One is the fetch
     * that never had to wait twice; sixty-four is a long poll a busy partition wakes over and over.
     */
    @Param({"1", "8", "64"})
    public int wakeups;

    private Path dataDirectory;
    private LongPollFetchProbe probe;

    /**
     * Opens a partition of its own for this trial, so that a count never includes a file another trial
     * opened.
     *
     * @throws IOException if the directory cannot be created
     */
    @Setup(Level.Trial)
    public void openTheProbe() throws IOException {
        dataDirectory = TemporaryDataDirectory.create("shrike-bench-long-poll-");
        probe = LongPollFetchProbe.open(dataDirectory);
    }

    /**
     * Ends the probe's producer, closes the partition, and removes everything this trial appended.
     *
     * @throws IOException if the directory cannot be removed
     */
    @TearDown(Level.Trial)
    public void closeTheProbe() throws IOException {
        probe.close();
        TemporaryDataDirectory.delete(dataDirectory);
    }

    /**
     * Serves one long poll that is woken {@code wakeups} times before it has bytes enough to answer.
     *
     * @param counted where the opens and the wakeups of this fetch are added, so that JMH reports them
     *                beside the score
     * @return how many bytes the fetch was answered with, returned so the work cannot be eliminated
     */
    @Benchmark
    public int serveOneLongPollWokenSeveralTimes(OpenCount counted) {
        LongPollFetchProbe.LongPollServed served = probe.serveOneLongPollWokenSeveralTimes(wakeups);
        counted.segmentFileOpens += served.segmentFileOpens();
        counted.fetchWakeups += served.wakeups();
        counted.longPollFetches++;
        return served.recordBytes();
    }

    /**
     * The three counts this benchmark exists for, which JMH writes into its result file beside the score
     * as secondary metrics.
     *
     * <p>All three are published rather than two, because how JMH normalizes an event counter is
     * something a reader should not have to know: with the fetches counted beside the opens, opens per
     * fetch is a ratio of two numbers in the same file however either of them is scaled.
     */
    @AuxCounters(AuxCounters.Type.EVENTS)
    @State(Scope.Thread)
    public static class OpenCount {

        /** {@code open(2)} calls on a segment file, counted through the partition's own seam. */
        public long segmentFileOpens;

        /** Times a fetch registered as a waiter and was then woken by an appended record. */
        public long fetchWakeups;

        /** Long polls served, which is the invocation count this benchmark's counters are read against. */
        public long longPollFetches;

        /** JMH keeps a counter across an iteration and this is where it starts each one. */
        @Setup(Level.Iteration)
        public void startFromNothing() {
            segmentFileOpens = 0L;
            fetchWakeups = 0L;
            longPollFetches = 0L;
        }
    }
}
