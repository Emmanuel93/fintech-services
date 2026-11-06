package com.fintech.audit.domain.failure;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A message a service could not handle after its retries, as it reached the dead letter topic.
 *
 * @param headers the original headers, base64-encoded by name: what a replay sends back
 */
public record FailedMessage(
        UUID failedMessageId,
        String dltTopic, int dltPartition, long dltOffset,
        String originalTopic, Integer originalPartition, Long originalOffset,
        String consumerGroup, String service, String correlationId,
        String messageKey, String payload, Map<String, String> headers,
        String exceptionClass, String exceptionMessage,
        Instant failedAt, Instant receivedAt,
        String status, int replays, String resolvedBy, Instant resolvedAt, String resolutionNote) {

    public static final String PENDING = "PENDING";
    public static final String REPLAYED = "REPLAYED";
    public static final String DISCARDED = "DISCARDED";
}
