package io.shrike.core.net;

import io.shrike.core.group.GroupOffsetStore;
import io.shrike.core.log.CorruptRecordException;
import io.shrike.core.log.OffsetOutOfRangeException;
import io.shrike.core.log.RecordTooLargeException;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CommitOffsetResponse;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.CreateTopicResponse;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ProduceResponse;
import io.shrike.core.protocol.Request;
import java.util.Optional;

/**
 * Turns a request the codec accepted into the answer the broker owes it. This is where the protocol
 * stops and the broker's own state begins: the codec has already said the bytes are a legal request,
 * and everything left to decide is a question about what this broker holds.
 *
 * <p>It knows nothing about sockets, and nothing here writes a byte. That is what lets the fetch path
 * block for as long as its caller asked without a socket loop having to know it did.
 *
 * <p>Every topic name and group id that reaches this class has already been through
 * {@link io.shrike.core.protocol.SafeName}, so quoting one in a message cannot forge a log line and
 * cannot name a path outside the data directory.
 *
 * <p>The error codes are chosen once, here:
 *
 * <ul>
 *   <li>{@link ErrorCode#UNKNOWN_TOPIC_OR_PARTITION} — no such topic, or a partition number outside
 *       the count the topic was created with, including a negative one.
 *   <li>{@link ErrorCode#OFFSET_OUT_OF_RANGE} — a fetch below the first offset a partition still holds
 *       or past its high-water mark, or a commit past that mark. The mark itself is <em>in</em> range
 *       in both cases: it is where a caught-up consumer sits.
 *   <li>{@link ErrorCode#CORRUPT_RECORD} — a stored frame no longer matches its checksum.
 *   <li>{@link ErrorCode#FRAME_TOO_LARGE} — a produce record whose frame would exceed
 *       {@code max.record.bytes}. Checked for every record of a request before any of them is
 *       appended, so a request with one oversized record stores none of them.
 *   <li>{@link ErrorCode#INVALID_REQUEST} — a negative offset to commit, which the wire format allows
 *       and this broker does not.
 *   <li>{@link ErrorCode#TOPIC_ALREADY_EXISTS} — a repeat create, whatever partition count it asks
 *       for.
 * </ul>
 *
 * <p>Anything else that goes wrong is not answered here: it leaves as an exception, and the connection
 * turns it into {@link ErrorCode#INTERNAL} and a log line, because a broker that guesses at the
 * meaning of a failure it did not expect is a broker telling its callers something it does not know.
 */
final class RequestDispatcher {

    private final TopicRegistry topics;
    private final GroupOffsetStore groupOffsets;

    RequestDispatcher(TopicRegistry topics, GroupOffsetStore groupOffsets) {
        this.topics = topics;
        this.groupOffsets = groupOffsets;
    }

    /**
     * @param request a request the codec accepted
     * @return the body to answer with, or the one code that is the whole answer
     */
    Answer dispatch(Request request) {
        return switch (request) {
            case ProduceRequest produce -> append(produce);
            case FetchRequest fetch -> read(fetch);
            case CommitOffsetRequest commit -> commitOffset(commit);
            case CreateTopicRequest create -> createTopic(create);
        };
    }

    private Answer append(ProduceRequest request) {
        Optional<Partition> partition = topics.partition(request.topic(), request.partition());
        if (partition.isEmpty()) {
            return unknownTopicOrPartition(request.topic(), request.partition());
        }

        try {
            return new Answer.Served(new ProduceResponse(partition.get().produce(request.records())));
        } catch (RecordTooLargeException e) {
            return new Answer.Refused(ErrorCode.FRAME_TOO_LARGE, e.getMessage());
        }
    }

    private Answer read(FetchRequest request) {
        Optional<Partition> partition = topics.partition(request.topic(), request.partition());
        if (partition.isEmpty()) {
            return unknownTopicOrPartition(request.topic(), request.partition());
        }

        try {
            return new Answer.Served(partition.get().fetch(request.fetchOffset(), request.maxBytes(),
                    request.maxWaitMs(), request.minBytes()));
        } catch (OffsetOutOfRangeException e) {
            return new Answer.Refused(ErrorCode.OFFSET_OUT_OF_RANGE, e.getMessage());
        } catch (CorruptRecordException e) {
            return new Answer.Refused(ErrorCode.CORRUPT_RECORD, e.getMessage());
        }
    }

    private Answer commitOffset(CommitOffsetRequest request) {
        Optional<Partition> partition = topics.partition(request.topic(), request.partition());
        if (partition.isEmpty()) {
            return unknownTopicOrPartition(request.topic(), request.partition());
        }
        if (request.offset() < 0) {
            return new Answer.Refused(ErrorCode.INVALID_REQUEST, "a committed offset is the next offset to read, so "
                    + request.offset() + " cannot be one");
        }

        // A group is allowed to commit the high-water mark itself: that is what "I have read everything
        // there is" says, and the offset it names is the one it will ask for next.
        long highWaterMark = partition.get().highWaterMark();
        if (request.offset() > highWaterMark) {
            return new Answer.Refused(ErrorCode.OFFSET_OUT_OF_RANGE, "offset " + request.offset()
                    + " is past the high-water mark " + highWaterMark + " of topic=" + request.topic()
                    + " partition=" + request.partition());
        }

        groupOffsets.commit(request.groupId(), request.topic(), request.partition(), request.offset());
        return new Answer.Served(new CommitOffsetResponse());
    }

    private Answer createTopic(CreateTopicRequest request) {
        try {
            topics.create(request.name(), request.partitionCount());
            return new Answer.Served(new CreateTopicResponse());
        } catch (TopicAlreadyExistsException e) {
            return new Answer.Refused(ErrorCode.TOPIC_ALREADY_EXISTS, e.getMessage());
        }
    }

    private static Answer unknownTopicOrPartition(String topic, int partition) {
        return new Answer.Refused(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION,
                "this broker holds no topic=" + topic + " partition=" + partition);
    }
}
