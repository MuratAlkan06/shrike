package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ByteChannelsTest {

    private static final byte[] PAYLOAD = "a frame that must not be cut in half".getBytes(UTF_8);

    @TempDir
    Path dataDirectory;

    @Test
    void writesEveryByteWhenTheChannelAcceptsOnlyOneBytePerCall() throws IOException {
        OneBytePerCallChannel channel = new OneBytePerCallChannel();

        ByteChannels.writeFully(channel, ByteBuffer.wrap(PAYLOAD));

        assertArrayEquals(PAYLOAD, channel.received());
        assertEquals(PAYLOAD.length, channel.writeCalls(), "one call per byte proves the loop ran, not a single write");
    }

    @Test
    void writesTheSameBytesToAStubbornChannelAsToAFileChannel() throws IOException {
        OneBytePerCallChannel stubbornChannel = new OneBytePerCallChannel();
        Path file = dataDirectory.resolve("written-in-one-go.bin");

        ByteChannels.writeFully(stubbornChannel, ByteBuffer.wrap(PAYLOAD));
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteChannels.writeFully(fileChannel, ByteBuffer.wrap(PAYLOAD));
        }

        assertArrayEquals(Files.readAllBytes(file), stubbornChannel.received());
    }

    @Test
    void readsTheWholeBufferBackFromTheRequestedPosition() throws IOException {
        Path file = dataDirectory.resolve("read-back.bin");
        Files.write(file, PAYLOAD);
        int skippedBytes = 2;

        ByteBuffer destination = ByteBuffer.allocate(PAYLOAD.length - skippedBytes);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteChannels.readFully(fileChannel, destination, skippedBytes);
        }

        assertEquals(0, destination.remaining(), "readFully must fill the buffer it was given");
        assertArrayEquals(Arrays.copyOfRange(PAYLOAD, skippedBytes, PAYLOAD.length), destination.array());
    }

    @Test
    void stopsShortWhenTheFileEndsBeforeTheBufferIsFull() throws IOException {
        Path file = dataDirectory.resolve("too-short.bin");
        Files.write(file, PAYLOAD);

        ByteBuffer destination = ByteBuffer.allocate(PAYLOAD.length + 8);
        try (FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteChannels.readFully(fileChannel, destination, 0L);
        }

        assertEquals(8, destination.remaining(), "the caller must be able to see that the file ended early");
    }

    /**
     * Accepts one byte per {@code write} call, which is legal for a channel and fatal for any caller
     * that assumes a single write drains its buffer.
     */
    private static final class OneBytePerCallChannel implements WritableByteChannel {

        private final ByteArrayOutputStream received = new ByteArrayOutputStream();

        // confined to: the test thread that created this stub
        private int writeCalls;
        private boolean open = true;

        @Override
        public int write(ByteBuffer source) {
            writeCalls++;
            if (!source.hasRemaining()) {
                return 0;
            }
            received.write(source.get());
            return 1;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        byte[] received() {
            return received.toByteArray();
        }

        int writeCalls() {
            return writeCalls;
        }
    }
}
