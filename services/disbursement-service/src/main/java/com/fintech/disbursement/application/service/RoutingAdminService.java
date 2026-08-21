package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.port.in.ManageRoutingUseCase;
import com.fintech.disbursement.application.port.out.CompanyMappingRepository;
import com.fintech.disbursement.application.port.out.RoutingRuleRepository;
import com.fintech.disbursement.domain.CompanyMapping;
import com.fintech.disbursement.domain.NoRoutingRuleException;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.domain.RoutingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class RoutingAdminService implements ManageRoutingUseCase {

    private static final Logger log = LoggerFactory.getLogger(RoutingAdminService.class);

    private final RoutingRuleRepository rules;
    private final CompanyMappingRepository mappings;

    public RoutingAdminService(RoutingRuleRepository rules, CompanyMappingRepository mappings) {
        this.rules = rules;
        this.mappings = mappings;
    }

    @Override
    @Transactional
    public RoutingRule createRule(UUID companyId, Rail rail, Provider provider,
                                  BigDecimal minAmount, BigDecimal maxAmount, int priority) {
        if (maxAmount != null && minAmount != null && maxAmount.compareTo(minAmount) < 0) {
            throw new IllegalArgumentException("maxAmount no puede ser menor que minAmount");
        }
        return rules.save(RoutingRule.of(companyId, rail, provider, minAmount, maxAmount, priority));
    }

    @Override
    @Transactional
    public RoutingRule setRuleEnabled(UUID routingRuleId, boolean enabled) {
        RoutingRule rule = rules.findById(routingRuleId)
                .orElseThrow(() -> new NoRoutingRuleException("No existe la regla " + routingRuleId));
        if (enabled) {
            rule.enable();
        } else {
            rule.disable();
        }
        return rules.save(rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoutingRule> listRules() {
        return rules.findAll();
    }

    /**
     * Alta o corrección del mapeo. Si ya existía, se <strong>reasigna</strong>.
     *
     * <p>Devolver en silencio el mapeo viejo sería el peor comportamiento posible: operación
     * corrige una empresa mal dada, recibe un 2xx con el valor anterior, y los desembolsos siguen
     * firmándose con la llave equivocada sin que nadie se entere (DB-07).
     */
    @Override
    @Transactional
    public CompanyMapping mapCompany(String sourceSystem, String sourceKey, UUID companyId) {
        return mappings.find(sourceSystem, sourceKey)
                .map(existing -> {
                    if (!existing.getCompanyId().equals(companyId)) {
                        log.warn("Reasignando mapeo sourceSystem={} sourceKey={} de {} a {}",
                                sourceSystem, sourceKey, existing.getCompanyId(), companyId);
                    }
                    existing.reassign(companyId);
                    return mappings.save(existing);
                })
                .orElseGet(() -> mappings.save(CompanyMapping.of(sourceSystem, sourceKey, companyId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompanyMapping> listMappings() {
        return mappings.findAll();
    }
}
