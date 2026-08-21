package com.fintech.notifications.application.port.out;

import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationPolicy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPolicyRepository {
    Optional<NotificationPolicy> findById(UUID policyId);
    Optional<NotificationPolicy> findActiveByEventType(EventType eventType);

    /** La política de una clave de evento, venga del catálogo interno o de un emisor externo. */
    Optional<NotificationPolicy> findActiveByEventKey(String eventKey);
    List<NotificationPolicy> findAllActive();
    int maxVersionFor(EventType eventType);
    NotificationPolicy save(NotificationPolicy policy);
}
