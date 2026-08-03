package io.shrike.core.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Replaces a small file with new contents so that a reader — including a reader after a power cut —
 * sees either the old file or the new one, never a half-written one.
 *
 * <p>The steps are fixed and their order is the whole point: write the bytes to a temporary file
 * <em>in the same directory</em>, force that file to the device, move it onto the target with
 * {@link StandardCopyOption#ATOMIC_MOVE}, and force the directory so the rename itself is on the
 * device. A caller told that a write succeeded has been told that all four happened.
 *
 * <p>It lives beside the log because this package already owns this codebase's answer to "when is
 * this durable": {@link ByteChannels#writeFully} is here, {@link ShrikeIOException} is here, and the
 * broker's two small metadata files — the topic registry and a consumer group's committed offsets —
 * should not each grow their own version of a sequence that is only correct in one order.
 *
 * <p>Not for the log's own segments: those are large, append-only, and forced in place. This is for
 * files small enough to rewrite whole.
 *
 * <p>Forcing a directory means opening it for reading, which POSIX allows and Windows does not. This
 * build is developed and tested on POSIX systems; on a platform that refuses, a write fails loudly
 * rather than quietly promising less than it delivers.
 */
public final class DurableFile {

    /** The suffix of the temporary file, which always sits in the target's own directory. */
    private static final String TEMPORARY_SUFFIX = ".tmp";

    private DurableFile() {
    }

    /**
     * The steps replacing a file takes, in the order they run.
     */
    public enum Step {

        /** The new contents are in a temporary file in the target's directory. */
        WRITTEN,

        /** That temporary file is on the device. */
        FORCED,

        /** The temporary file has taken the target's name in one atomic move. */
        MOVED,

        /** The directory holding it is on the device, so the move itself survives a power cut. */
        DIRECTORY_FORCED
    }

    /**
     * Told about each {@link Step} as it completes, once that step has run.
     *
     * <p>It exists because this sequence leaves no trace a JVM can otherwise observe. A test reads it to
     * prove that a caller is told about a write <em>after</em> the write was made durable and not before.
     * A caller that has to undo something when a write fails reads it for the other half of the same
     * fact: the move is what gives the new bytes the target's name, so a failure reported before it left
     * the old file in place, and a failure reported after it did not. Most callers want
     * {@link #IGNORED}.
     */
    @FunctionalInterface
    public interface StepObserver {

        /** Watches nothing, which is what every production caller wants. */
        StepObserver IGNORED = step -> {
        };

        /**
         * @param step the step that just finished
         */
        void completed(Step step);
    }

    /**
     * Replaces {@code target} with {@code contents}, durably.
     *
     * @param target   the file to replace, whose parent directory must already exist
     * @param contents the bytes the file should hold
     * @throws ShrikeIOException if any step fails, in which case the target still holds whatever it
     *                           held before
     */
    public static void replace(Path target, byte[] contents) {
        replace(target, contents, StepObserver.IGNORED);
    }

    /**
     * Replaces {@code target} with {@code contents}, durably, telling {@code observer} about each step
     * as it completes.
     *
     * @param target   the file to replace, whose parent directory must already exist
     * @param contents the bytes the file should hold
     * @param observer the seam described on {@link StepObserver}
     * @throws ShrikeIOException if any step fails, in which case the target still holds whatever it
     *                           held before
     */
    public static void replace(Path target, byte[] contents, StepObserver observer) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(observer, "observer");

        Path directory = target.getParent();
        if (directory == null) {
            throw new IllegalArgumentException("target must name a file inside a directory, but was " + target);
        }
        // The temporary file is a sibling on purpose: a rename is only atomic within one filesystem,
        // and a temporary directory elsewhere on the machine is not guaranteed to be on this one.
        Path temporary = directory.resolve(target.getFileName() + TEMPORARY_SUFFIX);

        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteChannels.writeFully(channel, ByteBuffer.wrap(contents));
                observer.completed(Step.WRITTEN);
                channel.force(true);
                observer.completed(Step.FORCED);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            observer.completed(Step.MOVED);
            forceDirectory(directory);
            observer.completed(Step.DIRECTORY_FORCED);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot replace " + target + " through " + temporary, e);
        }
    }

    /**
     * Gives a file a different name in the same directory, durably: one atomic move, then the directory
     * itself forced, so a reader after a power cut finds the file under one name or the other and never
     * under neither.
     *
     * <p>These are the last two steps of {@link #replace(Path, byte[])} and none of the others, because
     * the bytes are already on the device: nothing here is written and nothing is copied. What asks for
     * it is a startup that has to move a file onto the name this build stores it under.
     *
     * <p>An atomic move takes the target's name whether or not something else already holds it, so a
     * caller that has not established that nothing does is asking to lose a file.
     *
     * @param source the file as it is named now
     * @param target the name it should have, in the same directory
     * @throws IllegalArgumentException if the two do not name files in one directory
     * @throws ShrikeIOException        if the move or the force fails, in which case the file still has
     *                                  the name it had
     */
    public static void rename(Path source, Path target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        Path directory = target.getParent();
        if (directory == null || !directory.equals(source.getParent())) {
            throw new IllegalArgumentException("source and target must name files in one directory, but were "
                    + source + " and " + target);
        }

        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        } catch (IOException e) {
            throw new ShrikeIOException("cannot rename " + source + " to " + target, e);
        }
    }

    /**
     * Forces a directory itself, which is what puts a rename on the device rather than only the bytes
     * the renamed file holds.
     *
     * @param directory the directory to force
     * @throws IOException if the directory cannot be opened or forced
     */
    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
