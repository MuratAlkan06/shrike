package io.shrike.core.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SafeNameTest {

    @Test
    void acceptsLettersDigitsDotsUnderscoresAndDashes() {
        assertTrue(SafeName.isValid("orders"));
        assertTrue(SafeName.isValid("orders.eu-west_1"));
        assertTrue(SafeName.isValid("A"));
        assertTrue(SafeName.isValid("0"));
        assertTrue(SafeName.isValid("...a"));
        assertTrue(SafeName.isValid("a".repeat(SafeName.MAX_LENGTH_CHARS)));
    }

    @Test
    void refusesNamesThatCouldNameAFileOutsideTheirDirectory() {
        assertFalse(SafeName.isValid("."));
        assertFalse(SafeName.isValid(".."));
        assertFalse(SafeName.isValid("../orders"));
        assertFalse(SafeName.isValid("orders/0"));
        assertFalse(SafeName.isValid("orders\\0"));
        assertFalse(SafeName.isValid("/etc/passwd"));
        assertFalse(SafeName.isValid("C:orders"));
        assertFalse(SafeName.isValid("orders\0.log"));
    }

    @Test
    void refusesAnEmptyNameAndOneOverTheLengthCap() {
        assertFalse(SafeName.isValid(""));
        assertFalse(SafeName.isValid(null));
        assertFalse(SafeName.isValid("a".repeat(SafeName.MAX_LENGTH_CHARS + 1)));
    }

    @Test
    void refusesCharactersOutsideThePlainAsciiSet() {
        assertFalse(SafeName.isValid("ordérs"));
        assertFalse(SafeName.isValid("orders "));
        assertFalse(SafeName.isValid("orders\n"));
        assertFalse(SafeName.isValid("ord�rs"), "a byte sequence that is not UTF-8 decodes to this");
    }

    @Test
    void namesTheFieldAndPrintsNoControlCharacterWhenItRefuses() {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> SafeName.require("orders\n0", "topic"));

        assertTrue(refusal.getMessage().startsWith("topic must be"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("\"orders?0\""), refusal.getMessage());
    }

    @Test
    void cutsARejectedNameToTheLengthTheRuleAllowsBeforeQuotingIt() {
        String farTooLong = "a".repeat(10_000);

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> SafeName.require(farTooLong, "groupId"));

        assertTrue(refusal.getMessage().contains("cut from 10000 characters"), refusal.getMessage());
        assertTrue(refusal.getMessage().length() < 400, "a rejected name must not carry a log line away with it");
    }
}
