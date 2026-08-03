package io.shrike.core.log;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A range of whole record frames that is still in the segment file it was appended to: the file, the
 * byte position the range starts at, and how many bytes it occupies. It is what {@link Log#openRange}
 * hands back, and it is the shape a fetch takes when the broker sends the log's bytes without reading
 * them into memory first.
 *
 * <p><strong>The channel is this range's own.</strong> The log opens a second descriptor on the
 * segment file rather than lending out the one the segment reads and writes through, and whoever
 * holds the range closes it when the bytes have gone. That one rule is what makes a transfer safe to
 * leave running while retention deletes underneath it: retention closes the <em>segment's</em>
 * channel and unlinks the two names, and POSIX keeps an inode and its blocks alive until the last
 * descriptor on them is closed — so a transfer already under way reads on to the end of what it was
 * promised, from the file it opened. Nothing counts references and nothing is deferred; the range
 * holding a descriptor of its own is the whole of it.
 *
 * <p>The bytes in the range need not have been forced. A transfer reads through the same page cache
 * the append wrote into, so a record acknowledged a moment ago is readable here whether or not it has
 * reached the device — and the range never covers more than the high-water mark, because the offset
 * it was located against is already bounded by it.
 *
 * <p>An empty range holds no file and no descriptor: {@link #transferTo} writes nothing and
 * {@link #close()} has nothing to close. That is the shape a fetch at the high-water mark takes, and
 * it is why the two nullable fields below are nullable — they are present when and only when
 * {@link #lengthBytes()} is above zero.
 */
public final class RecordRange implements Closeable {

    /** The JDK's own logger, because {@code shrike-core} depends on the JDK alone. */
    private static final System.Logger LOGGER = System.getLogger(RecordRange.class.getName());

    /** No bytes, no file, no descriptor. Shared because there is nothing in it to own. */
    private static final RecordRange NO_RECORDS = new RecordRange(null, null, 0L, 0);

    /** The segment file the range lives in; null when and only when {@link #lengthBytes} is 0. */
    private final Path logFile;

    /** This range's own descriptor on {@link #logFile}; null on the same terms. */
    private final FileChannel channel;

    private final long positionBytes;
    private final int lengthBytes;

    private RecordRange(Path logFile, FileChannel channel, long positionBytes, int lengthBytes) {
        this.logFile = logFile;
        this.channel = channel;
        this.positionBytes = positionBytes;
        this.lengthBytes = lengthBytes;
    }

    /**
     * @return the range a fetch with nothing to read is answered with
     */
    static RecordRange empty() {
        return NO_RECORDS;
    }

    /**
     * @param logFile       the segment file the range lives in
     * @param channel       a descriptor on that file which this range owns and closes
     * @param positionBytes the byte position the first frame starts at
     * @param lengthBytes   how many bytes of whole frames the range covers, at least one
     * @return the range
     */
    static RecordRange of(Path logFile, FileChannel channel, long positionBytes, int lengthBytes) {
        Objects.requireNonNull(logFile, "logFile");
        Objects.requireNonNull(channel, "channel");
        if (lengthBytes < 1) {
            throw new IllegalArgumentException("a range over a file covers at least one byte, but this one covers "
                    + lengthBytes + "; a range with no bytes is RecordRange.empty()");
        }
        return new RecordRange(logFile, channel, positionBytes, lengthBytes);
    }

    /**
     * @return the byte position in the segment file the first frame of this range starts at
     */
    public long positionBytes() {
        return positionBytes;
    }

    /**
     * @return how many bytes of whole record frames this range covers, which is the number a response
     *         header promises before a single one of them is sent
     */
    public int lengthBytes() {
        return lengthBytes;
    }

    /**
     * Sends every byte of this range to {@code destination}, looping until all of them have gone.
     *
     * <p>{@link FileChannel#transferTo} is allowed to move fewer bytes than it was asked for, exactly
     * as a socket write is, so this loops for the same reason {@code writeFully} does: half a range is
     * half a promise, and the length field of the response it belongs to has already been sent.
     *
     * @param destination where the bytes go, which for a fetch is the client's socket
     * @throws IOException if the file or the destination fails, or if the file ends before the range
     *                     it was located as does — which cannot happen while this range holds its own
     *                     channel open, and is reported rather than looped on if it ever does
     */
    public void transferTo(WritableByteChannel destination) throws IOException {
        Objects.requireNonNull(destination, "destination");

        long readPositionBytes = positionBytes;
        long remainingBytes = lengthBytes;
        while (remainingBytes > 0) {
            long transferredBytes = channel.transferTo(readPositionBytes, remainingBytes, destination);
            if (transferredBytes <= 0) {
                throw new IOException("the segment " + logFile + " gave " + (lengthBytes - remainingBytes)
                        + " of the " + lengthBytes + " bytes the range from position " + positionBytes
                        + " declared");
            }
            readPositionBytes += transferredBytes;
            remainingBytes -= transferredBytes;
        }
    }

    /**
     * Closes this range's descriptor, which is when the space behind an unlinked segment comes back.
     */
    @Override
    public void close() {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            // Logged rather than thrown, and this is the one place in this package that makes that
            // trade. A range is closed once its bytes are already with whoever asked for them, so
            // throwing here would let a broker answer a request it had just finished answering — a
            // second frame written on top of a complete one, which is worse than a descriptor that
            // did not close. The file is named so an operator can see which one it was.
            LOGGER.log(System.Logger.Level.WARNING, "cannot close the channel opened on " + logFile
                    + " to send the range from position " + positionBytes, e);
        }
    }

    @Override
    public String toString() {
        return "RecordRange[" + (lengthBytes == 0 ? "empty" : logFile + " positionBytes=" + positionBytes
                + " lengthBytes=" + lengthBytes) + "]";
    }
}
