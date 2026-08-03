package io.shrike.core.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry's read-only view of itself: which topics are open and how many partitions each has.
 *
 * <p>Everything about creating one is proved by the broker tests that go through the wire. What is
 * proved here is the enumeration a describe is answered from, including the order it comes back in.
 */
class TopicRegistryTest {

    @TempDir
    Path dataDirectory;

    @Test
    void enumeratesEveryOpenTopicByNameWithThePartitionsItWasCreatedWith() {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            registry.create("orders", 3);
            registry.create("events", 1);

            List<Topic> topics = registry.topics();

            assertEquals(List.of("events", "orders"), topics.stream().map(Topic::name).toList(),
                    "topics come back by name, so two brokers holding the same topics answer the same way");
            assertEquals(List.of(1, 3), topics.stream().map(Topic::partitionCount).toList());
        }
    }

    @Test
    void enumeratesTheSpellingATopicWasCreatedWithRatherThanTheOneACallerAsksUnder() {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            registry.create("Orders", 1);

            List<Topic> topics = registry.topics();

            assertEquals(List.of("Orders"), topics.stream().map(Topic::name).toList(),
                    "the registry file and the partition directories carry that spelling, so a describe does too");
            assertTrue(registry.topic("ORDERS").isPresent(), "and it still answers to any casing of it");
        }
    }

    @Test
    void enumeratesNoTopicsForABrokerThatHoldsNone() {
        try (TopicRegistry registry = TopicRegistry.open(BrokerHarness.config(dataDirectory),
                BrokerHarness.SYSTEM_CLOCK)) {
            List<Topic> topics = registry.topics();

            assertEquals(List.of(), topics, "a broker with no topics has none to describe, which is not a failure");
        }
    }
}
