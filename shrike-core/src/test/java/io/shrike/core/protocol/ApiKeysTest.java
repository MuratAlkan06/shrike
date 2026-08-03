package io.shrike.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApiKeysTest {

    /** The first number no api has taken. Nothing implements it, so a request naming it is refused. */
    private static final short UNTAKEN_API_KEY = 6;

    @Test
    void freezesTheWireNumberOfEveryApiKey() {
        assertEquals(0, ApiKeys.PRODUCE);
        assertEquals(1, ApiKeys.FETCH);
        assertEquals(2, ApiKeys.COMMIT_OFFSET);
        assertEquals(3, ApiKeys.CREATE_TOPIC);
        assertEquals(4, ApiKeys.DESCRIBE_TOPICS);
        assertEquals(5, ApiKeys.DESCRIBE_GROUP);
        assertEquals(0, ApiKeys.VERSION_0);
    }

    @Test
    void implementsEveryApiKeyThatHasARequestBody() {
        assertTrue(ApiKeys.isImplemented(ApiKeys.PRODUCE));
        assertTrue(ApiKeys.isImplemented(ApiKeys.FETCH));
        assertTrue(ApiKeys.isImplemented(ApiKeys.COMMIT_OFFSET));
        assertTrue(ApiKeys.isImplemented(ApiKeys.CREATE_TOPIC));
        assertTrue(ApiKeys.isImplemented(ApiKeys.DESCRIBE_TOPICS));
        assertTrue(ApiKeys.isImplemented(ApiKeys.DESCRIBE_GROUP));
    }

    @Test
    void treatsANumberNoApiHasTakenAsNotImplemented() {
        assertFalse(ApiKeys.isImplemented(UNTAKEN_API_KEY));
        assertFalse(ApiKeys.isImplemented((short) -1));
        assertFalse(ApiKeys.isImplemented(Short.MAX_VALUE));
        assertFalse(ApiKeys.isImplemented(Short.MIN_VALUE));
    }

    @Test
    void speaksVersionZeroOfEveryImplementedApiKeyAndNoOtherVersion() {
        assertTrue(ApiKeys.isSupportedVersion(ApiKeys.PRODUCE, ApiKeys.VERSION_0));
        assertTrue(ApiKeys.isSupportedVersion(ApiKeys.FETCH, ApiKeys.VERSION_0));
        assertTrue(ApiKeys.isSupportedVersion(ApiKeys.COMMIT_OFFSET, ApiKeys.VERSION_0));
        assertTrue(ApiKeys.isSupportedVersion(ApiKeys.CREATE_TOPIC, ApiKeys.VERSION_0));
        assertTrue(ApiKeys.isSupportedVersion(ApiKeys.DESCRIBE_TOPICS, ApiKeys.VERSION_0));
        assertTrue(ApiKeys.isSupportedVersion(ApiKeys.DESCRIBE_GROUP, ApiKeys.VERSION_0));

        assertFalse(ApiKeys.isSupportedVersion(ApiKeys.PRODUCE, (short) 1));
        assertFalse(ApiKeys.isSupportedVersion(ApiKeys.FETCH, (short) -1));
        assertFalse(ApiKeys.isSupportedVersion(ApiKeys.DESCRIBE_GROUP, (short) 1));
        assertFalse(ApiKeys.isSupportedVersion(UNTAKEN_API_KEY, ApiKeys.VERSION_0));
    }
}
