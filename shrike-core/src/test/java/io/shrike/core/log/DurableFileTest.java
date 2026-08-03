package io.shrike.core.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
