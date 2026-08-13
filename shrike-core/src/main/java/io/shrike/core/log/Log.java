package io.shrike.core.log;

import java.io.Closeable;

/**
 * The append-only record log of one topic partition. Records go in at the end and come back out by
 * offset, where an offset is a logical record number and never a byte count. Nothing is ever rewritten
 * in place; the only thing that leaves a log is whole segments of its oldest records, which is what
 * {@link #deleteRetiredSegments(long)} does and what {@link #logStartOffset()} then reports.
 *
 * <p>A log has a single writer. Nothing here is safe to call from two threads at once.
 */
public interface Log extends Closeable {

    /**
     * @return the topic this log stores
     */
    String topic();

    /**
     * @return the partition of that topic this log stores; 0 is a partition like any other
     */
    int partition();

    /**
     * Appends one record to the end of the log.
     *
     * @param record the record to store; its value must not be {@code null}
     * @return the offset assigned to the record, one higher than the previous append and 0 for the
     *         first record in the log
     * @throws NullPointerException   if the record or its value is {@code null}, because a null value
     *                                would be a tombstone and compaction is a non-goal
     * @throws RecordTooLargeException if the framed record would exceed {@code max.record.bytes}
     * @throws ShrikeIOException       if the write fails
     */
    long append(ProducedRecord record);

    /**
     * Reads the record stored at {@code offset}.
     *
     * @param offset the logical record number to read
     * @return the stored record, with the offset and timestamp it was appended with
     * @throws OffsetOutOfRangeException if the offset is negative or is at or past {@link #nextOffset()}
     * @throws CorruptRecordException    if the bytes on disk no longer match their checksum
     * @throws ShrikeIOException         if the read fails
     */
    StoredRecord read(long offset);

    /**
     * Reads a range of the log back as the bytes it holds, without decoding them.
     *
     * <p>This is what a fetch response carries: whole record frames, back to back, copied verbatim
     * out of the segment file. Nothing is re-serialized on the way, so what a consumer receives is
     * what is on disk, byte for byte.
     *
     * <p>The range starts at {@code fetchOffset} and stops at the first of: {@code limitOffset},
     * {@code maxBytes}, or the end of the segment {@code fetchOffset} falls in. A range that stops at
     * a segment boundary is a short answer rather than an error — the caller fetches again from where
     * it got to. One exception to {@code maxBytes}: when the very first frame is larger than it, that
     * frame is returned anyway, because the alternative is a consumer that can never move past a
     * record it is allowed to store.
     *
     * @param fetchOffset the logical record number to start at; {@link #nextOffset()} is legal and
     *                    yields no bytes
     * @param limitOffset the exclusive logical record number to stop before, clamped to
     *                    {@link #nextOffset()} so no answer can cross the high-water mark
     * @param maxBytes    the most bytes to return, subject to the whole-frame rule above
     * @return the frames in that range, back to back, and never a partial one
     * @throws IllegalArgumentException  if {@code maxBytes} is negative
     * @throws OffsetOutOfRangeException if {@code fetchOffset} is below the first readable offset or
     *                                   past {@link #nextOffset()}
     * @throws CorruptRecordException    if a frame in the range contradicts what the log knows
     * @throws ShrikeIOException         if the read fails
     */
    byte[] readRange(long fetchOffset, long limitOffset, int maxBytes);

    /**
     * Locates the same range {@link #readRange} would return and opens it instead of reading it: the
     * caller is handed the file, the byte position, and the byte count, and sends the bytes wherever
     * they are going itself.
     *
     * <p>Every rule above applies unchanged — where the range starts, where it stops, the whole-frame
     * rule and its first-frame exception — because both methods ask the same segment the same
     * question and only differ in what they do with the answer. That is deliberate: the bytes a
     * consumer receives must not depend on which of the two served them.
     *
     * <p>The range holds a channel of its own, so the caller closes it once the bytes have gone. What
     * that buys is stated on {@link RecordRange}: a transfer already under way is not disturbed by
     * retention deleting the segment it is reading.
     *
     * @param fetchOffset the logical record number to start at; {@link #nextOffset()} is legal and
     *                    yields an empty range
     * @param limitOffset the exclusive logical record number to stop before, clamped to
     *                    {@link #nextOffset()} so no range can cross the high-water mark
     * @param maxBytes    the most bytes to cover, subject to the whole-frame rule above
     * @return the range, which the caller closes
     * @throws IllegalArgumentException  if {@code maxBytes} is negative
     * @throws OffsetOutOfRangeException if {@code fetchOffset} is below the first readable offset or
     *                                   past {@link #nextOffset()}
     * @throws CorruptRecordException    if a frame in the range contradicts what the log knows
     * @throws ShrikeIOException         if the segment cannot be read or opened
     */
    RecordRange openRange(long fetchOffset, long limitOffset, int maxBytes);

    /**
     * @return the offset the next append will take, which is also the exclusive upper bound of the
     *         readable offsets: the high-water mark
     */
    long nextOffset();

    /**
     * @return the lowest offset this log can still serve, which is the inclusive lower bound of the
     *         readable offsets. It is 0 until retention deletes something, and it only ever moves
     *         forward; a read below it is refused with this number, so a consumer that fell behind
     *         retention can reset to the oldest record that still exists
     */
    long logStartOffset();

    /**
     * Deletes the oldest stored records this log is no longer configured to keep, in whole segments.
     *
     * <p>This is the one operation that removes acknowledged records, so it is deliberately something
     * a caller asks for at a moment of its choosing rather than something an append does on the side.
     * What "no longer kept" means is the implementation's policy, and it is stated there.
     *
     * @param nowMillis the epoch millisecond retention is evaluated at, from the caller's time source
     * @return how many segments were deleted
     * @throws ShrikeIOException if a segment's files cannot be removed
     */
    int deleteRetiredSegments(long nowMillis);

    /**
     * Forces the records this log has not yet put on the device, when the configured flush policy says
     * the time bound has been reached.
     *
     * <p>This is the time half of {@code flush.mode}, and it is deliberately a synchronous method a
     * caller invokes at a moment of its choosing rather than a schedule the log keeps for itself: the
     * broker's {@code shrike-flush} thread is what asks, and a test asks by hand. The volume half —
     * {@code flush.interval.bytes} — is decided by the append that crosses the bound, because that is
     * the only moment at which the bound can be observed being crossed.
     *
     * <p>In {@link FlushMode#PER_RECORD} there is never anything unforced to find, so this does
     * nothing.
     *
     * @param nowMillis the epoch millisecond the interval is measured against, from the caller's clock
     * @return whether anything was forced
     * @throws ShrikeIOException if the force fails
     */
    boolean flushIfDue(long nowMillis);

    /**
     * Whether this log holds records it has not yet put on the device, which is the only question here
     * a caller may ask without whatever guard it appends and reads under.
     *
     * <p>That exception to the single-writer rule above is the whole point of the method: the thread
     * that asks {@link #flushIfDue(long)} once an interval wants to skip the logs that have nothing to
     * force, and taking a lock to find out is the cost it is trying to avoid. Implementations say how
     * they publish it.
     *
     * <p>What it promises is one-sided, and that is what makes it safe to act on. An answer of
     * {@code false} means every record this log has taken was on the device at some instant during the
     * call, so there was nothing to force; an append landing immediately afterwards makes it
     * {@code true} again and is found by the next ask. It never answers {@code false} for a log holding
     * unforced records for longer than it takes the append that made them to return.
     *
     * @return whether anything is waiting to be forced
     */
    boolean hasUnforcedBytes();

    /**
     * Closes the log's file. Implementations state in their own documentation what closing means for
     * durability.
     *
     * @throws ShrikeIOException if the file cannot be closed
     */
    @Override
    void close();
}
