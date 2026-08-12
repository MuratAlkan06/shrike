package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The handshake file, and the one thing about it that is not about its two keys: where it is written.
 *
 * <p>Every other file this broker replaces lives inside a data directory it owns alone, which a start
 * takes an exclusive lock on. The ready file does not: it is a cross-process artifact, so it is put
 * wherever the harness that started the broker can read it, and a shared temporary directory is the
 * ordinary place for that. Whoever else can write there can guess the name of the temporary file this
 * write goes through, which is why what happens to one planted there is worth a test of its own.
 */
class ReadyFileTest {

    private static final int PORT = 51_515;
    private static final long PID = 4242L;

    @TempDir
    Path sharedDirectory;

    @Test
    void writesTheTwoKeysTheHarnessReadsThePortAndPidFrom() throws IOException {
        Path readyFile = sharedDirectory.resolve("shrike.ready");

        ReadyFile.write(readyFile, PORT, PID);

        Properties handshake = new Properties();
        handshake.load(new StringReader(Files.readString(readyFile, UTF_8)));
        assertEquals(String.valueOf(PORT), handshake.getProperty(ReadyFile.PORT_KEY));
        assertEquals(String.valueOf(PID), handshake.getProperty(ReadyFile.PID_KEY));
    }

    @Test
    void deletesASymbolicLinkPlantedAtItsTemporaryFileInASharedDirectoryRatherThanWritingThroughIt()
            throws IOException {
        Path readyFile = sharedDirectory.resolve("shrike.ready");
        Path somebodyElsesFile = sharedDirectory.resolve("somebody-elses-file");
        Files.writeString(somebodyElsesFile, "not this broker's to write\n", UTF_8);
        plantSymbolicLinkOrAbort(sharedDirectory.resolve("shrike.ready.tmp"), somebodyElsesFile);

        ReadyFile.write(readyFile, PORT, PID);

        assertEquals("not this broker's to write\n", Files.readString(somebodyElsesFile, UTF_8),
                "a broker announcing its port does not empty a file it was pointed at through a link");
        assertFalse(Files.isSymbolicLink(readyFile),
                "and the handshake is a file of the broker's own rather than the link moved onto its name");
        assertEquals("port=" + PORT + "\npid=" + PID + "\n", Files.readString(readyFile, UTF_8),
                "while the harness waiting on it still reads the port and the pid");
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
