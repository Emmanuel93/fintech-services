package com.fintech.disbursement.application.port.out;

import com.fintech.disbursement.domain.RoutingRule;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutingRuleRepository {

    RoutingRule save(RoutingRule rule);

    Optional<RoutingRule> findById(UUID routingRuleId);

    /** Todas las reglas habilitadas. El filtro fino lo hace el dominio ({@code RoutingRule#covers}). */
    List<RoutingRule> findAllEnabled();

    List<RoutingRule> findAll();
}
