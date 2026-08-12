package io.shrike.core.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Whole-buffer transfers over NIO channels.
 *
 * <p>A single {@code channel.write(buffer)} is allowed to write fewer bytes than the buffer holds,
 * which would leave half a record frame on disk — or half a response frame on a socket. Every frame
 * this broker writes therefore goes through {@link #writeFully}, and a bare {@code channel.write} of
 * a frame is a bug. It is public for that reason and no other: there is one such loop in this
 * codebase, so the network layer writes its response frames with the same one the log writes its
 * records with, rather than a second copy that could be more forgiving.
 */
public final class ByteChannels {

    private ByteChannels() {
    }

    /**
     * Writes every remaining byte of {@code buffer}, looping until the buffer is drained. The
     * channel is assumed to be blocking, which is what a file channel is and what the broker's
     * sockets are; a non-blocking channel that keeps accepting nothing would spin here.
     *
     * @param channel destination channel
     * @param buffer  source buffer, drained to its limit
     * @throws IOException if the channel fails mid-write
     */
    public static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        writeFully(channel, buffer, WriteProgress.UNWATCHED);
    }

    /**
     * The same loop, telling {@code progress} each time bytes have actually gone. A caller that has a
     * deadline for a write which is making no headway needs to know when the last headway was, and
     * this is the only place that knows: the loop is inside a call that has not returned.
     *
     * <p>No single call asks for more than {@link WriteProgress#MAX_BYTES_BETWEEN_REPORTS}, because a
     * blocking write of a whole four-mebibyte answer is one call that says nothing until all four have
     * gone. The buffer is walked through views of that size; what reaches the channel, and in what
     * order, is the same either way.
     *
     * @param channel  destination channel
     * @param buffer   source buffer, drained to its limit
     * @param progress told what left, each time some of it does; a write that moved nothing is not
     *                 progress and is not reported
     * @throws IOException if the channel fails mid-write
     */
    public static void writeFully(WritableByteChannel channel, ByteBuffer buffer, WriteProgress progress)
            throws IOException {
        while (buffer.hasRemaining()) {
            int askBytes = (int) Math.min(buffer.remaining(), WriteProgress.MAX_BYTES_BETWEEN_REPORTS);
            ByteBuffer ask = buffer.slice(buffer.position(), askBytes);
            while (ask.hasRemaining()) {
                int bytesWritten = channel.write(ask);
                if (bytesWritten > 0) {
                    progress.advanced(bytesWritten);
                }
            }
            buffer.position(buffer.position() + askBytes);
        }
    }

    /**
     * Fills {@code buffer} from {@code positionBytes} onwards, looping over short reads and leaving
     * the channel's own file position untouched. A file that ends early stops the loop; the caller
     * detects that with {@code buffer.hasRemaining()} and decides what a truncated read means.
     *
     * @param channel       source channel
     * @param buffer        destination buffer, filled to its limit
     * @param positionBytes byte position in the file to start reading at
     * @throws IOException if the channel fails mid-read
     */
    static void readFully(FileChannel channel, ByteBuffer buffer, long positionBytes) throws IOException {
        long readPositionBytes = positionBytes;
        while (buffer.hasRemaining()) {
            int bytesRead = channel.read(buffer, readPositionBytes);
            if (bytesRead < 0) {
                return;
            }
            readPositionBytes += bytesRead;
        }
    }
}
