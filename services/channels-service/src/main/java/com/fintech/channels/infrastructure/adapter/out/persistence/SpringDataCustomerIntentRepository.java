package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.domain.CustomerIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCustomerIntentRepository extends JpaRepository<CustomerIntent, UUID> {

    Optional<CustomerIntent> findByIntentIdAndSessionId(UUID intentId, UUID sessionId);

    List<CustomerIntent> findBySessionId(UUID sessionId);

    @Query("SELECT ci FROM CustomerIntent ci WHERE ci.sessionId = :sessionId AND ci.status = 'CAPTURED'")
    List<CustomerIntent> findCapturedBySessionId(@Param("sessionId") UUID sessionId);
}
