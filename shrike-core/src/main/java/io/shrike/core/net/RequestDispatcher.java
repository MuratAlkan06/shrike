package io.shrike.core.net;

import io.shrike.core.group.CommittedOffset;
import io.shrike.core.group.GroupOffsetStore;
import io.shrike.core.group.TooManyGroupsException;
import io.shrike.core.log.CorruptRecordException;
import io.shrike.core.log.OffsetOutOfRangeException;
import io.shrike.core.log.RecordTooLargeException;
import io.shrike.core.log.SafeName;
import io.shrike.core.protocol.CommitOffsetRequest;
import io.shrike.core.protocol.CommitOffsetResponse;
import io.shrike.core.protocol.CreateTopicRequest;
import io.shrike.core.protocol.CreateTopicResponse;
import io.shrike.core.protocol.DescribeGroupRequest;
import io.shrike.core.protocol.DescribeGroupResponse;
import io.shrike.core.protocol.DescribeTopicsRequest;
import io.shrike.core.protocol.DescribeTopicsResponse;
import io.shrike.core.protocol.ErrorCode;
import io.shrike.core.protocol.FetchRequest;
import io.shrike.core.protocol.FetchResponse;
import io.shrike.core.protocol.GroupOffset;
import io.shrike.core.protocol.PartitionDescription;
import io.shrike.core.protocol.ProduceRequest;
import io.shrike.core.protocol.ProduceResponse;
import io.shrike.core.protocol.Request;
import io.shrike.core.protocol.TopicDescription;
import java.util.ArrayList;
import java.util.List;
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
 * {@link io.shrike.core.log.SafeName}, so quoting one in a message cannot forge a log line and
 * cannot name a path outside the data directory.
 *
 * <p>The error codes are chosen once, here:
 *
 * <ul>
 *   <li>{@link ErrorCode#UNKNOWN_TOPIC_OR_PARTITION} — no such topic, or a partition number outside
 *       the count the topic was created with, including a negative one. A describe that <em>names</em>
 *       a topic this broker does not hold earns it too; a describe that names none of them asks for
 *       every topic there is, and a broker holding none answers that with no topics.
 *   <li>{@link ErrorCode#OFFSET_OUT_OF_RANGE} — a fetch below the first offset a partition still holds
 *       or past its high-water mark, or a commit past that mark. The mark itself is <em>in</em> range
 *       in both cases: it is where a caught-up consumer sits. This is the one refusal that answers
 *       with a number too — the offset the partition now starts at — because a consumer whose
 *       committed offset has fallen behind retention has to be told where to resume, and the code on
 *       its own does not say.
 *   <li>{@link ErrorCode#CORRUPT_RECORD} — a stored frame no longer matches its checksum.
 *   <li>{@link ErrorCode#FRAME_TOO_LARGE} — a produce record whose frame would exceed
 *       {@code max.record.bytes}. Checked for every record of a request before any of them is
 *       appended, so a request with one oversized record stores none of them.
 *   <li>{@link ErrorCode#INVALID_REQUEST} — a negative offset to commit, which the wire format allows
 *       and this broker does not, a create that would push this broker past the partitions it may
 *       hold open, or a commit that would create a consumer group past the groups it may hold. The
 *       last two are one code because they are one kind of answer: a request this broker understood
 *       and will not act on, whose remedy is a number an operator raises rather than anything the
 *       caller can retry into.
 *   <li>{@link ErrorCode#TOPIC_ALREADY_EXISTS} — a repeat create, whatever partition count it asks
 *       for, including one whose name differs from an existing topic's only in case.
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
            case DescribeTopicsRequest describeTopics -> describeTopics(describeTopics);
            case DescribeGroupRequest describeGroup -> describeGroup(describeGroup);
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
            // Two shapes in, two shapes out: a fetch whose records were copied into memory is an
            // ordinary body, and one whose records are still in the file is the answer the connection
            // has to write a header for and then send. Nothing here decides which; the partition
            // already did, from the setting it was started with.
            return switch (partition.get().fetch(request.fetchOffset(), request.maxBytes(), request.maxWaitMs(),
                    request.minBytes())) {
                case FetchedRecords.Copied copied -> new Answer.Served(new FetchResponse(copied.highWaterMark(),
                        copied.records()));
                case FetchedRecords.Pinned pinned -> new Answer.Streamed(pinned.highWaterMark(), pinned.records());
            };
        } catch (OffsetOutOfRangeException e) {
            // The offset comes off the exception rather than from a second call on the partition: it
            // is what the range was when the read was refused, and asking again could answer with a
            // start offset that has moved since.
            return new Answer.OutOfRange(e.logStartOffset(), e.getMessage());
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
            return new Answer.OutOfRange(partition.get().logStartOffset(), "offset " + request.offset()
                    + " is past the high-water mark " + highWaterMark + " of topic=" + request.topic()
                    + " partition=" + request.partition());
        }

        try {
            groupOffsets.commit(request.groupId(), request.topic(), request.partition(), request.offset());
            return new Answer.Served(new CommitOffsetResponse());
        } catch (TooManyGroupsException e) {
            // The same code the partition budget answers with, because it is the same kind of refusal:
            // the request is legal and the broker holds as many of the thing it asks for as it may.
            return new Answer.Refused(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }

    private Answer createTopic(CreateTopicRequest request) {
        try {
            topics.create(request.name(), request.partitionCount());
            return new Answer.Served(new CreateTopicResponse());
        } catch (TopicAlreadyExistsException e) {
            return new Answer.Refused(ErrorCode.TOPIC_ALREADY_EXISTS, e.getMessage());
        } catch (TooManyPartitionsException e) {
            return new Answer.Refused(ErrorCode.INVALID_REQUEST, e.getMessage());
        }
    }

    /**
     * A named topic this broker does not hold is refused; a request naming none of them is asking about
     * every topic there is, and a broker holding none of those answers with an empty list. The two are
     * different questions rather than the same question with different answers: a caller that spelled a
     * topic out is owed the news that it is not here, and a caller that spelled nothing out has asked
     * something an empty broker answers truthfully.
     */
    private Answer describeTopics(DescribeTopicsRequest request) {
        List<Topic> described;
        if (request.describesEveryTopic()) {
            described = topics.topics();
        } else {
            described = new ArrayList<>(request.topics().size());
            for (String name : request.topics()) {
                Optional<Topic> topic = topics.topic(name);
                if (topic.isEmpty()) {
                    return new Answer.Refused(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION,
                            "this broker holds no topic=" + name);
                }
                described.add(topic.get());
            }
        }

        List<TopicDescription> descriptions = new ArrayList<>(described.size());
        for (Topic topic : described) {
            descriptions.add(describe(topic));
        }
        return new Answer.Served(new DescribeTopicsResponse(descriptions));
    }

    /**
     * Each partition's five numbers are read in one pass under that partition's own lock, so a
     * description names one instant of that partition rather than five. Nothing holds a lock across two
     * partitions, so a topic's description can straddle an append to one of them — which is what a
     * report about a broker that is still taking writes is.
     */
    private static TopicDescription describe(Topic topic) {
        List<PartitionDescription> partitions = new ArrayList<>(topic.partitionCount());
        for (Partition partition : topic.partitions()) {
            PartitionStatistics statistics = partition.statistics();
            // What a partition costs is one number to whoever is watching a disk fill up, and the split
            // between records and the index that finds them is the log package's business.
            long bytes = statistics.logBytes() + statistics.indexBytes();
            partitions.add(new PartitionDescription(partition.partition(), statistics.logStartOffset(),
                    statistics.highWaterMark(), statistics.segmentCount(), bytes));
        }
        return new TopicDescription(SafeName.fold(topic.name()), partitions);
    }

    /**
     * A group this broker has never heard of is answered with no entries rather than refused. A commit
     * is what creates a group — there is no create-group api and no group registry — so "no such group"
     * and "a group that has committed nothing" are one state, and an error code that claimed to tell
     * them apart would be claiming to know something this broker does not.
     */
    private Answer describeGroup(DescribeGroupRequest request) {
        List<CommittedOffset> committed = groupOffsets.committedOffsets(request.groupId());

        List<GroupOffset> offsets = new ArrayList<>(committed.size());
        for (CommittedOffset offset : committed) {
            offsets.add(new GroupOffset(offset.topic(), offset.partition(), offset.offset()));
        }
        return new Answer.Served(new DescribeGroupResponse(offsets));
    }

    private static Answer unknownTopicOrPartition(String topic, int partition) {
        return new Answer.Refused(ErrorCode.UNKNOWN_TOPIC_OR_PARTITION,
                "this broker holds no topic=" + topic + " partition=" + partition);
    }
}
