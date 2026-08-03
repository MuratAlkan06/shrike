package io.shrike.core.net;

import io.shrike.core.log.LogStatistics;

/**
 * One partition's storage, as it stood at the instant it was read.
 *
 * <p>It is a snapshot rather than a live view on purpose. {@link LogStatistics} answers five questions
 * one at a time, and a caller asking them one at a time would be told about five different instants —
 * a high-water mark from before an append and a byte count from after it, which is a partition that
 * never existed. Taking all five under {@link Partition}'s own lock makes the five numbers describe one
 * moment, and copying them into a record is what lets the lock be released before anything formats
 * them.
 *
 * @param logStartOffset the lowest offset this partition can still serve
 * @param highWaterMark  the offset the next append will take, which is the exclusive end of the
 *                       readable range
 * @param segmentCount   how many segments the log spreads over, the last of which is taking appends
 * @param logBytes       the bytes the log files of every segment occupy
 * @param indexBytes     the bytes the index files of every segment occupy
 */
record PartitionStatistics(long logStartOffset, long highWaterMark, int segmentCount, long logBytes,
                           long indexBytes) {
}
