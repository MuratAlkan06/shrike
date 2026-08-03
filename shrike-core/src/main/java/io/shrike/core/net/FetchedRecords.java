package io.shrike.core.net;

import io.shrike.core.log.RecordRange;
import java.io.Closeable;
import java.util.Objects;

/**
 * What one fetch is to be answered with, in whichever of the two shapes {@code fetch.zero.copy} chose:
 * the record frames copied into memory, or the range of the segment file they are still in, held open.
 * Both carry the high-water mark they were read against, and both describe the same bytes — the range
 * is located once, by one walk in one place, and only what happens to it afterwards differs.
 *
 * <p>It is a sealed type rather than a nullable field because the two shapes are owed different things
 * on the way out: a copy holds nothing and needs nothing done to it, while a range holds a channel
 * that somebody has to close. A fetch that decides to keep waiting closes the answer it did not use,
 * which is why {@link #close()} is on both.
 */
sealed interface FetchedRecords extends Closeable {

    /**
     * @return the offset the partition would append next, read under the same lock the records were
     */
    long highWaterMark();

    /**
     * @return how many bytes of record frames this answer carries, which is the number a fetch's
     *         {@code minBytes} is compared against and the number its response header promises
     */
    int recordBytes();

    /**
     * Gives back whatever this answer is holding. Harmless to call on one that holds nothing.
     */
    @Override
    void close();

    /**
     * The records as bytes in memory, read out of the segment file by the connection thread that asked
     * for them.
     *
     * @param highWaterMark the mark they were read against
     * @param records       whole record frames, back to back
     */
    record Copied(long highWaterMark, byte[] records) implements FetchedRecords {

        public Copied {
            Objects.requireNonNull(records, "records");
        }

        @Override
        public int recordBytes() {
            return records.length;
        }

        @Override
        public void close() {
            // A copy owns nothing: the bytes are already here.
        }
    }

    /**
     * The records where they already are, with a channel of their own open on the file so that
     * retention cannot pull it out from under the transfer.
     *
     * @param highWaterMark the mark the range was located against
     * @param records       the range, which closing this closes
     */
    record Pinned(long highWaterMark, RecordRange records) implements FetchedRecords {

        public Pinned {
            Objects.requireNonNull(records, "records");
        }

        @Override
        public int recordBytes() {
            return records.lengthBytes();
        }

        @Override
        public void close() {
            records.close();
        }
    }
}
