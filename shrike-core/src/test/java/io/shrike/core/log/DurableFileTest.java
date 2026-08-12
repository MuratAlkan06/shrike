package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replacing a small file so that a reader sees the old contents or the new ones and never a mixture.
 */
class DurableFileTest {

    @TempDir
    Path directory;

    @Test
    void writesAFileThatWasNotThereBefore() throws IOException {
        Path target = directory.resolve("topics");

        DurableFile.replace(target, "shrike.topics v1\norders 4\n".getBytes(UTF_8));

        assertEquals("shrike.topics v1\norders 4\n", Files.readString(target, UTF_8));
    }

    @Test
    void replacesTheWholeOfAnExistingFileAndLeavesNoTemporaryBehind() throws IOException {
        Path target = directory.resolve("topics");
        DurableFile.replace(target, "a much longer set of contents than what follows it\n".getBytes(UTF_8));

        DurableFile.replace(target, "short\n".getBytes(UTF_8));

        assertEquals("short\n", Files.readString(target, UTF_8),
                "the new file replaces the old one whole, rather than overwriting its first bytes");
        assertFalse(Files.exists(directory.resolve("topics.tmp")),
                "the temporary file it was written through was moved, not copied");
        assertEquals(List.of("topics"), Files.list(directory).map(path -> path.getFileName().toString()).sorted()
                .toList());
    }

    /**
     * The temporary file's name is {@code <target>.tmp} and anybody who can write to the directory can
     * guess it, which matters wherever this build writes outside a directory the broker owns — the ready
     * file is the standing example. A link planted at that name is removed rather than opened, because
     * unlinking takes away the name and never the file behind it.
     */
    @Test
    void deletesASymbolicLinkPlantedAtItsTemporaryFileRatherThanWritingThroughIt() throws IOException {
        Path target = directory.resolve("topics");
        Path somebodyElsesFile = directory.resolve("somebody-elses-file");
        Files.writeString(somebodyElsesFile, "not this broker's to write\n", UTF_8);
        plantSymbolicLinkOrAbort(directory.resolve("topics.tmp"), somebodyElsesFile);

        DurableFile.replace(target, "shrike.topics v1\norders 4\n".getBytes(UTF_8));

        assertEquals("not this broker's to write\n", Files.readString(somebodyElsesFile, UTF_8),
                "the planted link was unlinked rather than opened, so the file at the end of it kept every byte");
        assertFalse(Files.isSymbolicLink(target),
                "and what took the target's name is a file this call created, not the link moved onto it");
        assertEquals("shrike.topics v1\norders 4\n", Files.readString(target, UTF_8),
                "the write still landed where it was asked to land");
        assertFalse(Files.exists(directory.resolve("topics.tmp"), LinkOption.NOFOLLOW_LINKS),
                "and nothing is left under the temporary name");
    }

    /**
     * A crash between creating the temporary file and moving it leaves one behind. The next write deletes
     * it and creates its own, rather than opening it and writing over what is there — which is the same
     * line of code that makes the test above pass, said from the ordinary side.
     */
    @Test
    void deletesAStaleTemporaryFileLeftBehindByACrashRatherThanBeingBlockedByIt() throws IOException {
        Path target = directory.resolve("topics");
        Files.writeString(directory.resolve("topics.tmp"), "half of a file a crash left behind\n", UTF_8);

        DurableFile.replace(target, "shrike.topics v1\norders 4\n".getBytes(UTF_8));

        assertEquals("shrike.topics v1\norders 4\n", Files.readString(target, UTF_8),
                "the stale temporary file cost the write nothing");
        try (Stream<Path> entries = Files.list(directory)) {
            assertEquals(List.of("topics"), entries.map(entry -> entry.getFileName().toString()).sorted().toList(),
                    "and none of it is left in the directory");
        }
    }

    @Test
    void reportsEveryStepItTookInTheOrderItTookThem() {
        List<DurableFile.Step> steps = new ArrayList<>();

        DurableFile.replace(directory.resolve("topics"), "contents\n".getBytes(UTF_8), steps::add);

        assertEquals(List.of(DurableFile.Step.WRITTEN, DurableFile.Step.FORCED, DurableFile.Step.MOVED,
                DurableFile.Step.DIRECTORY_FORCED), steps,
                "the bytes reach the device before the name does, and the name before the caller is told");
    }

    @Test
    void renamesAFileOntoAnotherNameInTheSameDirectory() throws IOException {
        Path source = directory.resolve("Readers.offsets");
        DurableFile.replace(source, "contents\n".getBytes(UTF_8));

        DurableFile.rename(source, directory.resolve("readers.offsets"));

        assertEquals(List.of("readers.offsets"), Files.list(directory).map(path -> path.getFileName().toString())
                .sorted().toList(), "one file, under the name it was moved to");
        assertEquals("contents\n", Files.readString(directory.resolve("readers.offsets"), UTF_8),
                "a rename moves the name and never the bytes");
    }

    @Test
    void refusesToRenameAcrossDirectories() {
        Path source = directory.resolve("topics");

        assertThrows(IllegalArgumentException.class,
                () -> DurableFile.rename(source, directory.resolve("elsewhere").resolve("topics")),
                "a move is atomic within one filesystem and this one forces one directory, so it renames in place");
    }

    @Test
    void failsWhenTheDirectoryItWouldWriteIntoIsNotThere() {
        Path target = directory.resolve("no-such-directory").resolve("topics");

        assertThrows(ShrikeIOException.class, () -> DurableFile.replace(target, "contents\n".getBytes(UTF_8)),
                "a replacement that cannot happen fails loudly rather than leaving the caller to assume");
    }

    /**
     * @param link        the name to plant a symbolic link under
     * @param destination what it should point at
     * @throws IOException if it cannot be created for any reason other than the filesystem refusing
     *                     symbolic links at all, which aborts the test that asked instead
     */
    private static void plantSymbolicLinkOrAbort(Path link, Path destination) throws IOException {
        try {
            Files.createSymbolicLink(link, destination);
        } catch (UnsupportedOperationException | FileSystemException e) {
            abort("this filesystem will not create a symbolic link, and a symbolic link is what this test is about");
        }
    }
}
