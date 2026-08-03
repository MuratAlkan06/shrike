package io.shrike.clients;

import java.util.zip.CRC32C;

/**
 * The records the process flow sends, named by the index they were sent under.
 *
 * <p>One place decides what record number {@code n} looks like and which partition it belongs to, so
 * the producer process, the consumer process, and the test that checks what came back cannot disagree
 * about it.
 */
final class KeyedRecords {

    private KeyedRecords() {
    }

    /**
     * @param index the record's number in the run
     * @return its key
     */
    static String key(int index) {
        return "key-" + index;
    }

    /**
     * @param index the record's number in the run
     * @return its payload
     */
    static String value(int index) {
        return "value-" + index;
    }

    /**
     * @param index          the record's number in the run
     * @param partitionCount how many partitions the topic has
     * @return the partition it is sent to. Round robin, because this build has no partitioner: the
     *         point is only that consecutive records land on different partitions
     */
    static int partitionOf(int index, int partitionCount) {
        return index % partitionCount;
    }

    /**
     * @param value a record's payload
     * @return the CRC32C of those bytes, in hex. It is what a producer process and a consumer process
     *         each write down about a record they handled, so a test can tell that the bytes one
     *         processed are the bytes the other sent without either of them quoting the payload
     */
    static String checksumOf(byte[] value) {
        CRC32C checksum = new CRC32C();
        checksum.update(value, 0, value.length);
        return "%08x".formatted((int) checksum.getValue());
    }
}
