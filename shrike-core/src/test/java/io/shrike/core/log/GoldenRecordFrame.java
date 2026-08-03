package io.shrike.core.log;

import java.util.HexFormat;

/**
 * One record's frame, frozen. The bytes below were derived from the format description in DESIGN.md,
 * not read back out of the implementation, and the crc32c value was computed with an independent
 * Castagnoli implementation.
 *
 * <pre>
 * 00000020            length     = 32 bytes follow the length field
 * aac8d9b3            crc32c     of the 28 bytes from magic through the end of the value
 * 00                  magic      = 0
 * 00                  attributes = 0, reserved
 * 0000000000000000    offset     = 0
 * 0000018bcfe56800    timestamp  = 1700000000000 epoch millis
 * 00000001            keyLen     = 1
 * 6b                  key        = "k"
 * 00000001            valueLen   = 1
 * 76                  value      = "v"
 * </pre>
 *
 * <p>It lives here rather than inside one test because two tests must agree on it: the log freezes
 * what it writes to disk, and the wire codec that a consumer uses reads the same frame back off a
 * socket. Two copies of these bytes could drift apart, and the drift would be the format quietly
 * forking in two.
 */
public final class GoldenRecordFrame {

    /** The offset the frame stores. */
    public static final long OFFSET = 0L;

    /** The timestamp the frame stores, in epoch milliseconds. */
    public static final long TIMESTAMP_MILLIS = 1_700_000_000_000L;

    /** The frame's key, as text. */
    public static final String KEY = "k";

    /** The frame's value, as text. */
    public static final String VALUE = "v";

    /** The whole frame, length field included. */
    public static final String HEX = "00000020aac8d9b3000000000000000000000000018bcfe56800000000016b0000000176";

    private GoldenRecordFrame() {
    }

    /**
     * @return a fresh copy of the frozen frame's bytes
     */
    public static byte[] bytes() {
        return HexFormat.of().parseHex(HEX);
    }
}
