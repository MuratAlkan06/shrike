package io.shrike.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaToolchainTest {

    @Test
    void runsTestsOnJava21Toolchain() {
        int featureVersion = Runtime.version().feature();

        assertEquals(21, featureVersion, "tests must run on the Java 21 toolchain, not the JVM that launched Maven");
    }
}
