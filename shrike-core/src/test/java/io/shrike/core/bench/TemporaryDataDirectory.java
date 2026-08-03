package io.shrike.core.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The data directory one benchmark trial runs against, created before it is measured and removed
 * after it. It is the benchmark's answer to the {@code @TempDir} the tests get from JUnit: every path
 * a benchmark touches is derived from it, so nothing is written beside the working directory and a
 * trial that appended gigabytes leaves nothing behind.
 */
final class TemporaryDataDirectory {

    private TemporaryDataDirectory() {
    }

    /**
     * @param prefix what the directory's name starts with, so a leaked one names the benchmark that
     *               leaked it
     * @return a new empty directory
     * @throws IOException if it cannot be created
     */
    static Path create(String prefix) throws IOException {
        return Files.createTempDirectory(prefix);
    }

    /**
     * Removes a directory and everything under it, deepest path first.
     *
     * @param directory the directory to remove
     * @throws IOException if it cannot be walked or a path under it cannot be deleted
     */
    static void delete(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
