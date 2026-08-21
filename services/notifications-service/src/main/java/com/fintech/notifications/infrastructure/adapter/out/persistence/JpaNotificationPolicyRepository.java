package com.fintech.notifications.infrastructure.adapter.out.persistence;

import com.fintech.notifications.application.port.out.NotificationPolicyRepository;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaNotificationPolicyRepository
        extends JpaRepository<NotificationPolicy, UUID>, NotificationPolicyRepository {

    @Override
    @Query("SELECT p FROM NotificationPolicy p WHERE p.eventType = :eventType AND p.status = 'ACTIVE'")
    Optional<NotificationPolicy> findActiveByEventType(@Param("eventType") EventType eventType);

    @Override
    @Query("SELECT p FROM NotificationPolicy p WHERE p.eventKey = :eventKey AND p.status = 'ACTIVE'")
    Optional<NotificationPolicy> findActiveByEventKey(@Param("eventKey") String eventKey);

    @Override
    @Query("SELECT p FROM NotificationPolicy p WHERE p.status = 'ACTIVE'")
    List<NotificationPolicy> findAllActive();

    @Override
    @Query("SELECT COALESCE(MAX(p.version), 0) FROM NotificationPolicy p WHERE p.eventType = :eventType")
    int maxVersionFor(@Param("eventType") EventType eventType);
}
