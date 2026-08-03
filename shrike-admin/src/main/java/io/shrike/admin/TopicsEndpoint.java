package io.shrike.admin;

import io.shrike.clients.ClientConfig;
import io.shrike.clients.ShrikeTopics;
import io.shrike.core.protocol.TopicDescription;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the broker holds: every topic, and then one topic in full.
 *
 * <p>Both endpoints are one describe over the wire and nothing else. Neither of them touches a file,
 * and there is no cache between them and the broker: what this facade reports is what the broker said
 * when it was asked, which for a broker that is still taking writes is the only honest kind of answer.
 *
 * <p><strong>A connection per request.</strong> Each call opens its own connection and closes it in the
 * same try-with-resources. DESIGN.md records why a long-lived shared one was turned down.
 */
@RestController
@RequestMapping("/api/v1/topics")
public class TopicsEndpoint {

    private final ClientConfig broker;

    /**
     * @param broker where the broker is, validated at startup
     */
    public TopicsEndpoint(ClientConfig broker) {
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    /**
     * @return every topic this broker holds, or none at all when it holds none
     */
    @GetMapping
    public TopicListing list() {
        try (ShrikeTopics topics = ShrikeTopics.open(broker)) {
            return TopicListing.of(topics.describeAll());
        }
    }

    /**
     * @param topic the topic to describe, matched case-insensitively because that is what a topic name
     *              is
     * @return that topic's partitions, each with its offsets, its segment count, and its bytes on disk
     */
    @GetMapping("/{topic}")
    public TopicDetail describe(@PathVariable("topic") String topic) {
        List<TopicDescription> described;
        try (ShrikeTopics topics = ShrikeTopics.open(broker)) {
            described = topics.describe(List.of(topic));
        } catch (IllegalArgumentException unusable) {
            // The request record applies the broker's own name rule before a byte is sent, and that is
            // the only thing here that can raise this. Naming it as such is what keeps some other
            // library's IllegalArgumentException from being answered as if the caller had caused it.
            throw new UnusableNameException(unusable);
        }
        // One name was asked about, so one description is owed: a broker that answers a describe with
        // a different number of topics has answered a different question, and this facade says so
        // rather than reading whichever entry happens to be first.
        if (described.size() != 1) {
            throw new IllegalStateException("a describe of one topic was answered with " + described.size()
                    + " topics");
        }
        return TopicDetail.of(described.get(0));
    }
}
