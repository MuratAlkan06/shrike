package io.shrike.core.log;

/**
 * Told each time bytes have actually left, by the two loops in this package that write until there is
 * nothing left to write: {@link ByteChannels#writeFully} and {@link RecordRange#transferTo}.
 *
 * <p>It exists because "this write is stuck" and "this write is slow" are different things and only
 * the caller can tell them apart. A loop against a blocking socket cannot: it is inside a call that
 * has not returned, and whether the peer is reading a kibibyte a second or has stopped reading
 * altogether looks the same from in there. So the loops say what left and when, and the broker's
 * connections use that to bound the time a write spends making no progress at all — a peer that keeps
 * taking bytes, however slowly, is never closed for it.
 *
 * <p>The count is bytes, not calls: a short write that moved nothing is not progress and is not
 * reported.
 */
@FunctionalInterface
public interface WriteProgress {

    /**
     * The most bytes either loop asks for in one call, and so the furthest apart two reports can be:
     * one mebibyte.
     *
     * <p>It exists because a blocking write does not come back to say how it is getting on. Both
     * {@code write(2)} and {@code sendfile(2)} against a blocking socket return once everything they
     * were asked for has gone, so a single call for a four-mebibyte answer is a call that reports
     * nothing at all until the whole answer has arrived — which is exactly the interval a peer that is
     * draining slowly would be judged over. Asking for a mebibyte at a time turns that into a report
     * per mebibyte, at the cost of one system call per mebibyte, on a path that moves gigabytes a
     * second. It is also what makes the write bound sayable in a sentence: what crosses it is a peer
     * that has not taken a mebibyte of its answer in {@code write.timeout.ms}.
     *
     * <p>The number is a mebibyte rather than something smaller because that is already far below any
     * rate a client on this machine reads at — a mebibyte per 30 seconds is 35 KiB a second — and
     * because a smaller ask buys a finer bound with more system calls for every fetch this broker
     * serves, including the ones nobody is stalling.
     */
    long MAX_BYTES_BETWEEN_REPORTS = 1024L * 1024L;

    /**
     * What a caller that is not watching passes. The log's own write paths use it — an append is
     * bounded by the device rather than by a peer — so a file write costs the same as it did before
     * this interface existed.
     */
    WriteProgress UNWATCHED = bytesWritten -> {
    };

    /**
     * @param bytesWritten how many bytes left in the call that just returned, always at least one
     */
    void advanced(long bytesWritten);
}
