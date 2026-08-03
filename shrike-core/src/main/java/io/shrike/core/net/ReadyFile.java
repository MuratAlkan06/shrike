package io.shrike.core.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.shrike.core.log.DurableFile;
import java.nio.file.Path;

/**
 * The handshake that says a broker is listening, and on which port.
 *
 * <p>The file holds exactly two keys and nothing else, in this order, each on its own line:
 *
 * <pre>
 * port=&lt;the port the broker bound&gt;
 * pid=&lt;the process the broker runs in&gt;
 * </pre>
 *
 * <p>That is {@link java.util.Properties}-parseable on purpose, and the format is frozen: a test
 * harness that starts a broker as a separate process reads the port from here — which is the only way
 * to learn it when the broker was started on port {@value BrokerConfig#EPHEMERAL_PORT} — and kills the
 * pid when it is done. Adding a key is allowed one day; renaming or reordering these two is not.
 *
 * <p>It is written through {@link DurableFile}, so it appears in one atomic move. A reader therefore
 * sees the whole file or no file, never a line and a half, and does not have to guess whether a file
 * it can see is finished.
 *
 * <p>Stopping a broker leaves the file where it is. A stopped broker and a crashed one then look the
 * same from the outside, which is honest: the file says "a broker was listening here", and whoever
 * starts a broker owns the path and points it somewhere it does not mind overwriting.
 */
public final class ReadyFile {

    /** The key holding the TCP port the broker bound, as decimal digits. */
    public static final String PORT_KEY = "port";

    /** The key holding the operating-system process id of the broker, as decimal digits. */
    public static final String PID_KEY = "pid";

    private ReadyFile() {
    }

    /**
     * Writes the ready file, replacing whatever was at that path.
     *
     * @param path the file to write, whose directory must already exist
     * @param port the port the broker bound
     * @param pid  the process the broker runs in
     * @throws io.shrike.core.log.ShrikeIOException if the file cannot be written
     */
    static void write(Path path, int port, long pid) {
        String contents = PORT_KEY + "=" + port + "\n" + PID_KEY + "=" + pid + "\n";
        DurableFile.replace(path, contents.getBytes(UTF_8));
    }
}
