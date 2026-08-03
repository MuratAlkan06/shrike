package io.shrike.clients;

import io.shrike.core.log.StoredRecord;
import java.util.List;

/**
 * What a consumer does with the records it was given, called by
 * {@link ShrikeConsumer#processThenCommit} before the offset moves.
 *
 * <p><strong>Returning normally is what commits.</strong> The offset to read next is stored only after
 * this method has returned for every record it was handed, so whatever the handler did with those
 * records — wrote them to a file, sent them somewhere, forced them to a device — happened first. A
 * handler that throws commits nothing, and the same records are read again the next time.
 *
 * <p>That ordering is the at-least-once trade, stated plainly: a process that dies between doing the
 * work and committing does the work again for those records when it is replaced. Duplicates are
 * possible; records are not lost. A handler that must not repeat its effect has to make that effect
 * repeatable itself — the offsets it is handed are what it can key that on.
 */
@FunctionalInterface
public interface RecordHandler {

    /**
     * @param partition the partition these records were read from, so one handler can serve every
     *                  partition a member owns
     * @param records   the records, in ascending offset order, never empty
     */
    void process(int partition, List<StoredRecord> records);
}
