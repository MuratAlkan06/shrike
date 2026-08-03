package io.shrike.clients;

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
}
