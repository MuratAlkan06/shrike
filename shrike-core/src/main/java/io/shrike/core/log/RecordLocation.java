package io.shrike.core.log;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where one record lives. Carried by every storage error so a message locates the byte a reader
 * choked on, per the rule that an error names topic, partition, offset, position, and file.
 *
 * @param topic         the topic the record belongs to
 * @param partition     the partition number within that topic
 * @param offset        the logical record number, counted in records from the start of the log
 * @param positionBytes the byte position of the record's frame within {@code file}
 * @param file          the log file holding the frame
 */
public record RecordLocation(String topic, int partition, long offset, long positionBytes, Path file) {

    public RecordLocation {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(file, "file");
    }
}
