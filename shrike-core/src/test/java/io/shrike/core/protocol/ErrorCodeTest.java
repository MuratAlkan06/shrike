package io.shrike.core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void freezesTheWireNumberOfEveryErrorCode() {
        assertEquals(0, ErrorCode.NONE.code());
        assertEquals(1, ErrorCode.UNKNOWN_TOPIC_OR_PARTITION.code());
        assertEquals(2, ErrorCode.OFFSET_OUT_OF_RANGE.code());
        assertEquals(3, ErrorCode.CORRUPT_RECORD.code());
        assertEquals(4, ErrorCode.FRAME_TOO_LARGE.code());
        assertEquals(5, ErrorCode.INVALID_REQUEST.code());
        assertEquals(6, ErrorCode.UNSUPPORTED_VERSION.code());
        assertEquals(7, ErrorCode.TOPIC_ALREADY_EXISTS.code());
        assertEquals(99, ErrorCode.INTERNAL.code());
    }

    @Test
    void findsEveryKnownCodeByTheNumberItTravelsAs() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            assertEquals(Optional.of(errorCode), ErrorCode.fromCode(errorCode.code()));
        }
    }

    @Test
    void findsNoCodeForANumberThisBuildDoesNotKnow() {
        assertTrue(ErrorCode.fromCode((short) 8).isEmpty());
        assertTrue(ErrorCode.fromCode((short) -1).isEmpty());
        assertTrue(ErrorCode.fromCode(Short.MAX_VALUE).isEmpty());
    }
}
