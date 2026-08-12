package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.shrike.core.log.FlushMode;
import io.shrike.core.log.LogConfig;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What a broker process makes of the environment it was started in. Nothing here opens a socket or
 * touches a file: reading a launch is a function of two maps, which is why it is the part that can be
 * tested exhaustively and the entry point around it is four statements.
 */
class BrokerLaunchTest {

    private static final String DATA_DIRECTORY = "/var/lib/shrike";
    private static final List<String> NO_ARGUMENTS = List.of();

    /**
     * The five settings that multiply what a caller can make this broker hold — the memory one
     * connection is worth, how many connections there may be, and the three times one of them may
     * hold its place while nothing moves — each beside the largest value it takes.
     */
    private static final List<Map.Entry<String, Integer>> CAPPED_SETTINGS = List.of(
            Map.entry(BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE, BrokerConfig.HIGHEST_MAX_REQUEST_BYTES),
            Map.entry(BrokerLaunch.CONNECTION_CAP_VARIABLE, BrokerConfig.HIGHEST_CONNECTION_CAP),
            Map.entry(BrokerLaunch.MAX_FETCH_WAIT_MS_VARIABLE, BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS),
            Map.entry(BrokerLaunch.READ_TIMEOUT_MS_VARIABLE, BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS),
            Map.entry(BrokerLaunch.WRITE_TIMEOUT_MS_VARIABLE, BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS));

    @Test
    void bindsTheLoopbackAddressWhenTheEnvironmentDoesNotAskForAWiderOne() {
        Map<String, String> environment = Map.of(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY);

        BrokerLaunch launch = BrokerLaunch.from(NO_ARGUMENTS, environment);

        assertEquals(InetAddress.getLoopbackAddress(), launch.bindAddress());
        assertTrue(launch.bindAddress().isLoopbackAddress(), "the default reaches no further than this machine");
    }

    @Test
    void defaultsToPortNineThousandSevenHundredAndFiftyAndAReadyFileInsideTheDataDirectory() {
        Map<String, String> environment = Map.of(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY);

        BrokerLaunch launch = BrokerLaunch.from(NO_ARGUMENTS, environment);

        assertEquals(9750, launch.config().port());
        assertEquals(Path.of(DATA_DIRECTORY), launch.config().dataDirectory());
        assertEquals(Path.of(DATA_DIRECTORY, "shrike.ready"), launch.config().readyFilePath(),
                "every path derives from the data directory, including this one");
    }

    @Test
    void bindsEveryInterfaceOnlyWhenTheEnvironmentSaysSoInSoManyWords() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.BIND_ADDRESS_VARIABLE, "0.0.0.0");

        BrokerLaunch launch = BrokerLaunch.from(NO_ARGUMENTS, environment);

        assertTrue(launch.bindAddress().isAnyLocalAddress());
        assertFalse(launch.bindAddress().isLoopbackAddress());
    }

    @Test
    void takesThePortAndTheReadyFileThatWereNamed() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.PORT_VARIABLE, "19750",
                BrokerLaunch.READY_FILE_VARIABLE, "/run/shrike/ready");

        BrokerLaunch launch = BrokerLaunch.from(NO_ARGUMENTS, environment);

        assertEquals(19750, launch.config().port());
        assertEquals(Path.of("/run/shrike/ready"), launch.config().readyFilePath());
    }

    @Test
    void runsTheDefaultsItAlwaysRanWhenNothingButTheDataDirectoryIsSet() {
        Map<String, String> environment = Map.of(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY);

        BrokerLaunch launch = BrokerLaunch.from(NO_ARGUMENTS, environment);

        Path dataDirectory = Path.of(DATA_DIRECTORY);
        assertEquals(BrokerConfig.defaults(dataDirectory, 9750, dataDirectory.resolve("shrike.ready")),
                launch.config(),
                "a broker told only where to store things runs what it ran before any of this could be set");
    }

    @Test
    void readsEverySettingFromTheVariableThatNamesIt() {
        Map<String, String> environment = Map.ofEntries(
                Map.entry(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY),
                Map.entry(BrokerLaunch.RETENTION_MS_VARIABLE, "86400000"),
                Map.entry(BrokerLaunch.RETENTION_BYTES_VARIABLE, "1073741824"),
                Map.entry(BrokerLaunch.FLUSH_MODE_VARIABLE, "per-record"),
                Map.entry(BrokerLaunch.FLUSH_INTERVAL_MS_VARIABLE, "250"),
                Map.entry(BrokerLaunch.FLUSH_INTERVAL_BYTES_VARIABLE, "2097152"),
                Map.entry(BrokerLaunch.SEGMENT_BYTES_VARIABLE, "67108864"),
                Map.entry(BrokerLaunch.MAX_RECORD_BYTES_VARIABLE, "524288"),
                Map.entry(BrokerLaunch.INDEX_INTERVAL_BYTES_VARIABLE, "8192"),
                Map.entry(BrokerLaunch.MAX_FETCH_WAIT_MS_VARIABLE, "5000"),
                Map.entry(BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE, "1048576"),
                Map.entry(BrokerLaunch.READ_TIMEOUT_MS_VARIABLE, "7500"),
                Map.entry(BrokerLaunch.WRITE_TIMEOUT_MS_VARIABLE, "9500"),
                Map.entry(BrokerLaunch.FETCH_ZERO_COPY_VARIABLE, "False"),
                Map.entry(BrokerLaunch.CONNECTION_CAP_VARIABLE, "8"),
                Map.entry(BrokerLaunch.MAX_TOTAL_PARTITIONS_VARIABLE, "32"),
                Map.entry(BrokerLaunch.MAX_TOTAL_GROUPS_VARIABLE, "16"));

        BrokerConfig config = BrokerLaunch.from(NO_ARGUMENTS, environment).config();

        Path dataDirectory = Path.of(DATA_DIRECTORY);
        LogConfig namedLogConfig = new LogConfig(524_288, 67_108_864, 8192, 86_400_000L, 1_073_741_824L,
                FlushMode.PER_RECORD, 250L, 2_097_152L);
        assertEquals(new BrokerConfig(dataDirectory, 9750, 1_048_576, 5_000, false, 8, 7_500, 9_500, 32, 16,
                        dataDirectory.resolve("shrike.ready"), namedLogConfig), config,
                "every value the environment named reached the one field its variable names");
        assertFalse(config.zeroCopyFetch(), "a fetch's records are read into memory because a variable said so");
        assertEquals(FlushMode.PER_RECORD, config.logConfig().flushMode(),
                "the durability promise a broker runs under is one an operator can now make");
    }

    @Test
    void takesEitherFlushModeSpellingInWhateverLettersItArrivesIn() {
        Map<String, String> shouted = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.FLUSH_MODE_VARIABLE, "PER-RECORD");
        Map<String, String> mixed = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.FLUSH_MODE_VARIABLE, "Interval");

        LogConfig fromShouted = BrokerLaunch.from(NO_ARGUMENTS, shouted).config().logConfig();
        LogConfig fromMixed = BrokerLaunch.from(NO_ARGUMENTS, mixed).config().logConfig();

        assertEquals(FlushMode.PER_RECORD, fromShouted.flushMode(),
                "an environment is typed by hand, so the letters it was typed in are not the setting");
        assertEquals(FlushMode.INTERVAL, fromMixed.flushMode(), "and the other spelling reads the same way");
    }

    @Test
    void refusesToStartWithoutADataDirectory() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, Map.of()));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.DATA_DIRECTORY_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("no default"), refusal.getMessage());
    }

    @Test
    void readsAVariableSetToNothingAsOneThatWasNotSet() {
        Map<String, String> blanks = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.PORT_VARIABLE, "",
                BrokerLaunch.BIND_ADDRESS_VARIABLE, "   ");

        BrokerLaunch launch = BrokerLaunch.from(NO_ARGUMENTS, blanks);

        assertEquals(9750, launch.config().port());
        assertTrue(launch.bindAddress().isLoopbackAddress());
        assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, Map.of(BrokerLaunch.DATA_DIRECTORY_VARIABLE, "  ")),
                "a data directory set to nothing is a data directory nobody named");
    }

    @Test
    void refusesAPortThatIsNotAWholeNumber() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.PORT_VARIABLE, "9750a");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.PORT_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("9750a"), refusal.getMessage());
    }

    @Test
    void refusesAPortOutsideTheRangeASocketCanBind() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.PORT_VARIABLE, String.valueOf(BrokerConfig.MAX_PORT + 1));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(String.valueOf(BrokerConfig.MAX_PORT)), refusal.getMessage());
    }

    /**
     * The address is garbage that begins with a colon, so it is refused as a malformed IPv6 literal on
     * the characters in hand. This test therefore asks nothing of a resolver, and a machine with no
     * network — or one whose resolver answers every name with an address of its own — runs it the same
     * way as any other.
     */
    @Test
    void refusesABindAddressThatIsNotAnAddress() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.BIND_ADDRESS_VARIABLE, ":not:an:address");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.BIND_ADDRESS_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains(":not:an:address"), refusal.getMessage());
    }

    @Test
    void readsANewVariableSetToNothingAsOneThatWasNotSet() {
        Map<String, String> blanks = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.SEGMENT_BYTES_VARIABLE, "",
                BrokerLaunch.FLUSH_MODE_VARIABLE, "   ");

        LogConfig logConfig = BrokerLaunch.from(NO_ARGUMENTS, blanks).config().logConfig();

        assertEquals(LogConfig.DEFAULT_SEGMENT_BYTES, logConfig.segmentBytes(),
                "docker run -e SHRIKE_SEGMENT_BYTES= is an operator saying nothing about segments");
        assertEquals(LogConfig.DEFAULT_FLUSH_MODE, logConfig.flushMode(),
                "and a variable holding only blanks says nothing either");
    }

    @Test
    void refusesASettingThatIsNotAWholeNumberNamingTheVariableAndQuotingWhatItWasGiven() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.SEGMENT_BYTES_VARIABLE, "128MiB");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.SEGMENT_BYTES_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("128MiB"), refusal.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, Map.of(
                        BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                        BrokerLaunch.RETENTION_MS_VARIABLE, "forever")),
                "the settings counted in longs are read the same way, and \"forever\" is not a number of them");
    }

    @Test
    void refusesANumericValueWithANewlineOnOneScrubbedLineRatherThanLettingItForgeASecond() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE, "1048576\n2026-01-01 FATAL forged log line");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("\n"),
                "a value carrying a newline is echoed on one line, so it cannot forge a second: "
                        + refusal.getMessage());
    }

    @Test
    void refusesAVeryLongNumericValueWithATruncatedMessageRatherThanFloodingStandardError() {
        String tenKilobytesOfDigits = "9".repeat(10_000);
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE, tenKilobytesOfDigits);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("cut from 10000 characters"),
                "a value too long to be a number is cut rather than echoed whole: " + refusal.getMessage());
        assertFalse(refusal.getMessage().contains(tenKilobytesOfDigits),
                "the ten-kilobyte value never reaches the log line in full");
    }

    @Test
    void refusesASettingOutsideItsRangeNamingTheVariableThatSetItAndKeepingTheRecordsSentence() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.SEGMENT_BYTES_VARIABLE, String.valueOf(LogConfig.MAX_SEGMENT_BYTES + 1));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.SEGMENT_BYTES_VARIABLE),
                "the record owns the bound and the launch says which variable crossed it: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("segmentBytes must not exceed "
                + LogConfig.MAX_SEGMENT_BYTES), "the record's own sentence is kept whole: " + refusal.getMessage());
        IllegalArgumentException capRefusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, Map.of(
                        BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                        BrokerLaunch.CONNECTION_CAP_VARIABLE, "0")));
        assertTrue(capRefusal.getMessage().contains(BrokerLaunch.CONNECTION_CAP_VARIABLE),
                "one pattern, whichever record holds the bound: " + capRefusal.getMessage());
    }

    @Test
    void refusesAReadTimeoutOfZeroNamingTheVariableThatWouldHaveClosedEveryConnection() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.READ_TIMEOUT_MS_VARIABLE, "0");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.READ_TIMEOUT_MS_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("readTimeoutMs must be at least 1"),
                "the record owns the bound and the launch says which variable crossed it: " + refusal.getMessage());
    }

    @Test
    void refusesAWriteTimeoutOfZeroNamingTheVariableThatWouldHaveClosedEveryAnswer() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.WRITE_TIMEOUT_MS_VARIABLE, "0");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.WRITE_TIMEOUT_MS_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("writeTimeoutMs must be at least 1"),
                "the record owns the bound and the launch says which variable crossed it: " + refusal.getMessage());
    }

    @Test
    void refusesEveryCappedSettingOneOverItsCeilingNamingTheVariableThatCrossedIt() {
        for (Map.Entry<String, Integer> setting : CAPPED_SETTINGS) {
            Map<String, String> overTheCeiling = Map.of(
                    BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                    setting.getKey(), String.valueOf(setting.getValue() + 1));

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> BrokerLaunch.from(NO_ARGUMENTS, overTheCeiling));

            assertTrue(refusal.getMessage().contains(setting.getKey()),
                    "a value past a ceiling is refused like any other value this broker cannot use: "
                            + refusal.getMessage());
            assertTrue(refusal.getMessage().contains("must not exceed " + setting.getValue()),
                    "the record owns the ceiling and the launch says which variable crossed it: "
                            + refusal.getMessage());
        }
    }

    @Test
    void takesEveryCappedSettingAtItsCeilingExactly() {
        Map<String, String> atTheCeiling = Map.ofEntries(
                Map.entry(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY),
                Map.entry(BrokerLaunch.MAX_REQUEST_BYTES_VARIABLE,
                        String.valueOf(BrokerConfig.HIGHEST_MAX_REQUEST_BYTES)),
                Map.entry(BrokerLaunch.CONNECTION_CAP_VARIABLE, String.valueOf(BrokerConfig.HIGHEST_CONNECTION_CAP)),
                Map.entry(BrokerLaunch.MAX_FETCH_WAIT_MS_VARIABLE,
                        String.valueOf(BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS)),
                Map.entry(BrokerLaunch.READ_TIMEOUT_MS_VARIABLE,
                        String.valueOf(BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS)),
                Map.entry(BrokerLaunch.WRITE_TIMEOUT_MS_VARIABLE,
                        String.valueOf(BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS)));

        BrokerConfig config = BrokerLaunch.from(NO_ARGUMENTS, atTheCeiling).config();

        assertEquals(BrokerConfig.HIGHEST_MAX_REQUEST_BYTES, config.maxRequestBytes(),
                "the ceiling is a value this broker takes, and only the one past it is refused");
        assertEquals(BrokerConfig.HIGHEST_CONNECTION_CAP, config.connectionCap());
        assertEquals(BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS, config.maxFetchWaitMs());
        assertEquals(BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS, config.readTimeoutMs());
        assertEquals(BrokerConfig.HIGHEST_CONNECTION_HOLD_MILLIS, config.writeTimeoutMs());
    }

    @Test
    void refusesAFlushModeSpellingThisBuildDoesNotKnowAndSaysWhichTwoItTakes() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.FLUSH_MODE_VARIABLE, "per-batch");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.FLUSH_MODE_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("per-batch"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("per-record") && refusal.getMessage().contains("interval"),
                "a spelling that promises nothing this build can keep is answered with the two that do: "
                        + refusal.getMessage());
    }

    @Test
    void refusesAZeroCopySettingThatIsNeitherTrueNorFalseRatherThanReadingItAsFalse() {
        Map<String, String> environment = Map.of(
                BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY,
                BrokerLaunch.FETCH_ZERO_COPY_VARIABLE, "yes");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(NO_ARGUMENTS, environment));

        assertTrue(refusal.getMessage().contains(BrokerLaunch.FETCH_ZERO_COPY_VARIABLE), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("yes"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("true") && refusal.getMessage().contains("false"),
                "a word that is not one of the two is refused rather than read as the second: "
                        + refusal.getMessage());
    }

    @Test
    void refusesAnArgumentRatherThanIgnoringIt() {
        Map<String, String> environment = Map.of(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(List.of("/some/other/data/directory"), environment));

        assertTrue(refusal.getMessage().contains("takes no arguments"), refusal.getMessage());
    }
}
