package com.fintech.banking.infrastructure.adapter.out.persistence;

import com.fintech.banking.domain.PayoutRoute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataPayoutRouteRepository extends JpaRepository<PayoutRoute, UUID> {

    List<PayoutRoute> findByEnabledTrue();
}
