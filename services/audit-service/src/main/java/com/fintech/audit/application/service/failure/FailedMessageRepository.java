package com.fintech.audit.application.service.failure;

import com.fintech.audit.domain.failure.FailedMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where the failure registry keeps the dead letters. */
public interface FailedMessageRepository {

    /** Records it once: a redelivered dead letter (same topic, partition and offset) is ignored. */
    boolean record(FailedMessage message);

    Optional<FailedMessage> findById(UUID id);

    List<FailedMessage> search(String correlationId, String originalTopic, String status, int page, int size);

    void markReplayed(UUID id, String by, String note);

    void markDiscarded(UUID id, String by, String note);
}
