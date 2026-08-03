package io.shrike.core.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;

/**
 * Whole-buffer transfers over NIO channels.
 *
 * <p>A single {@code channel.write(buffer)} is allowed to write fewer bytes than the buffer holds,
 * which would leave half a record frame on disk. Every frame written by this package therefore goes
 * through {@link #writeFully}, and a bare {@code channel.write} of a frame is a bug.
 */
final class ByteChannels {

    private ByteChannels() {
    }

    /**
     * Writes every remaining byte of {@code buffer}, looping until the buffer is drained. The
     * channel is assumed to be blocking, which is what a file channel is; a non-blocking channel
     * that keeps accepting nothing would spin here.
     *
     * @param channel destination channel
     * @param buffer  source buffer, drained to its limit
     * @throws IOException if the channel fails mid-write
     */
    static void writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
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
