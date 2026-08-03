package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void refusesAnArgumentRatherThanIgnoringIt() {
        Map<String, String> environment = Map.of(BrokerLaunch.DATA_DIRECTORY_VARIABLE, DATA_DIRECTORY);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> BrokerLaunch.from(List.of("/some/other/data/directory"), environment));

        assertTrue(refusal.getMessage().contains("takes no arguments"), refusal.getMessage());
    }
}
