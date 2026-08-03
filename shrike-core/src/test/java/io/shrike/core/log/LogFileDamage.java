package io.shrike.core.log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The ways a log or index file can come back wrong after a crash, done to real files with a
 * {@link RandomAccessFile} so recovery is tested against the filesystem rather than against a mock of
 * one. Every method here is what a power cut, a half-flushed page, or a stray bit looks like from the
 * outside; none of them go through the log's own code.
 *
 * <p>Damage a file only while no log has it open, so the damage is what the next open finds.
 */
final class LogFileDamage {

    private LogFileDamage() {
    }

    /**
     * Cuts a file short, as a crash between two writes does.
     *
     * @param file          the file to shorten
     * @param positionBytes the size to leave behind
     */
    static void truncateTo(Path file, long positionBytes) throws IOException {
        try (RandomAccessFile damaged = new RandomAccessFile(file.toFile(), "rw")) {
            damaged.setLength(positionBytes);
        }
    }

    /**
     * Flips the lowest bit of one byte, as a bad sector or a bad cable does.
     *
     * @param file          the file to damage
     * @param positionBytes the byte to flip a bit in
     */
    static void flipBitAt(Path file, long positionBytes) throws IOException {
        try (RandomAccessFile damaged = new RandomAccessFile(file.toFile(), "rw")) {
            damaged.seek(positionBytes);
            int original = damaged.readUnsignedByte();
            damaged.seek(positionBytes);
            damaged.write(original ^ 0x01);
        }
    }

    /**
     * Overwrites a big-endian int32 in place, which is how a corrupt length field is arranged.
     *
     * @param file          the file to damage
     * @param positionBytes where the int32 starts
     * @param value         the value to write there
     */
    static void writeIntAt(Path file, long positionBytes, int value) throws IOException {
        try (RandomAccessFile damaged = new RandomAccessFile(file.toFile(), "rw")) {
            damaged.seek(positionBytes);
            damaged.writeInt(value);
        }
    }

    /**
     * Appends zero bytes to a file, which is what a filesystem that extended a file but never wrote
     * the data leaves behind.
     *
     * @param file       the file to extend
     * @param zeroBytes  how many zero bytes to append
     */
    static void appendZeroBytes(Path file, int zeroBytes) throws IOException {
        try (RandomAccessFile damaged = new RandomAccessFile(file.toFile(), "rw")) {
            damaged.seek(damaged.length());
            damaged.write(new byte[zeroBytes]);
        }
    }

    /**
     * Deletes a file, which is what a careless operator or a half-finished rebuild leaves behind.
     *
     * @param file the file to delete
     */
    static void delete(Path file) throws IOException {
        Files.delete(file);
    }
}
