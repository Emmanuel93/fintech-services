package com.fintech.disbursement.application.port.in;

import com.fintech.disbursement.domain.CompanyMapping;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.domain.RoutingRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Cambiar de proveedor es dato, no despliegue (DC-7). */
public interface ManageRoutingUseCase {

    RoutingRule createRule(UUID companyId, Rail rail, Provider provider,
                           BigDecimal minAmount, BigDecimal maxAmount, int priority);

    RoutingRule setRuleEnabled(UUID routingRuleId, boolean enabled);

    List<RoutingRule> listRules();

    CompanyMapping mapCompany(String sourceSystem, String sourceKey, UUID companyId);

    List<CompanyMapping> listMappings();
}
