package com.fintech.disbursement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Regla de encaminamiento: (empresa, rail, rango de monto) → proveedor.
 *
 * <p>Es <strong>dato</strong>, no código. Cambiar de proveedor, repartir por monto o apagar un rail
 * para una empresa es un {@code INSERT}, no un despliegue — y es lo que hace sustituible al
 * proveedor sin tocar el dominio (DC-7).
 *
 * <p>{@code companyId} nulo = regla por defecto para todas las empresas. Gana siempre la de menor
 * {@code priority}; a igual prioridad, gana la específica de empresa.
 */
@Entity
@Table(schema = "disbursement", name = "routing_rules")
public class RoutingRule {

    @Id
    @Column(name = "routing_rule_id", nullable = false, updatable = false)
    private UUID routingRuleId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "rail", nullable = false)
    private String rail;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "min_amount", nullable = false)
    private BigDecimal minAmount;

    /** Nulo = sin tope superior. */
    @Column(name = "max_amount")
    private BigDecimal maxAmount;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RoutingRule() {
    }

    public static RoutingRule of(UUID companyId, Rail rail, Provider provider,
                                 BigDecimal minAmount, BigDecimal maxAmount, int priority) {
        RoutingRule rule = new RoutingRule();
        rule.routingRuleId = UUID.randomUUID();
        rule.companyId = companyId;
        rule.rail = rail.name();
        rule.provider = provider.name();
        rule.minAmount = minAmount != null ? minAmount : BigDecimal.ZERO;
        rule.maxAmount = maxAmount;
        rule.priority = priority;
        rule.enabled = true;
        rule.createdAt = Instant.now();
        return rule;
    }

    public boolean covers(UUID candidateCompany, Rail candidateRail, BigDecimal amount) {
        if (!enabled) return false;
        if (companyId != null && !companyId.equals(candidateCompany)) return false;
        if (!rail.equals(candidateRail.name())) return false;
        if (amount.compareTo(minAmount) < 0) return false;
        return maxAmount == null || amount.compareTo(maxAmount) <= 0;
    }

    /** A igual prioridad la regla específica de empresa manda sobre la genérica. */
    public int specificity() { return companyId != null ? 0 : 1; }

    public void disable() { this.enabled = false; }
    public void enable() { this.enabled = true; }

    public UUID getRoutingRuleId() { return routingRuleId; }
    public UUID getCompanyId() { return companyId; }
    public String getRail() { return rail; }
    public String getProvider() { return provider; }
    public Provider providerValue() { return Provider.valueOf(provider); }
    public BigDecimal getMinAmount() { return minAmount; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public int getPriority() { return priority; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
