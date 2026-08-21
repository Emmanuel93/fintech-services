package com.fintech.disbursement.infrastructure.adapter.out.persistence;

import com.fintech.disbursement.application.port.out.RoutingRuleRepository;
import com.fintech.disbursement.domain.RoutingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface JpaRoutingRuleRepository
        extends JpaRepository<RoutingRule, UUID>, RoutingRuleRepository {

    @Override
    @Query("SELECT r FROM RoutingRule r WHERE r.enabled = true")
    List<RoutingRule> findAllEnabled();
}
