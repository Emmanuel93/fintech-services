package com.fintech.banking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * (empresa, rail, rango de monto) → <b>cuenta propia + proveedor</b>.
 *
 * <p>Sustituye a {@code disbursement.routing_rules}, que decidía sólo el <b>proveedor</b>. La cuenta
 * la elegía el conector con un {@code is_default} por empresa: un default ciego al saldo, al costo y
 * al horario, que además obligaba a que cada proveedor nuevo trajera su copia del catálogo.
 *
 * <p>Es <b>dato</b>, no código: cambiar de proveedor, repartir por monto o mover una empresa a otra
 * cuenta es un {@code INSERT}.
 *
 * <p>{@code companyId} nulo = regla por defecto de todas las empresas. Gana la de menor
 * {@code priority}; a igual prioridad, la específica de empresa sobre la genérica.
 */
@Entity
@Table(name = "payout_routes", schema = "banking")
public class PayoutRoute {

    @Id
    @Column(name = "payout_route_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "rail", nullable = false, length = 20)
    private String rail;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "bank_account_id", nullable = false)
    private UUID bankAccountId;

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
    private OffsetDateTime createdAt;

    protected PayoutRoute() {}

    public static PayoutRoute of(UUID companyId, PayoutRail rail, PayoutProvider provider,
                                 UUID bankAccountId, BigDecimal minAmount, BigDecimal maxAmount,
                                 int priority) {
        if (bankAccountId == null) {
            // Una ruta sin cuenta es exactamente el defecto que este servicio corrige: decidiría el
            // proveedor y dejaría la cuenta al criterio del conector.
            throw new IllegalArgumentException("Una ruta sin cuenta ordenante no es una ruta");
        }
        PayoutRoute r = new PayoutRoute();
        r.id            = UUID.randomUUID();
        r.companyId     = companyId;
        r.rail          = rail.name();
        r.provider      = provider.name();
        r.bankAccountId = bankAccountId;
        r.minAmount     = minAmount != null ? minAmount : BigDecimal.ZERO;
        r.maxAmount     = maxAmount;
        r.priority      = priority;
        r.enabled       = true;
        r.createdAt     = OffsetDateTime.now();
        return r;
    }

    public boolean cubre(UUID candidata, PayoutRail candidatoRail, BigDecimal monto) {
        if (!enabled) return false;
        if (companyId != null && !companyId.equals(candidata)) return false;
        if (!rail.equals(candidatoRail.name())) return false;
        if (monto.compareTo(minAmount) < 0) return false;
        return maxAmount == null || monto.compareTo(maxAmount) <= 0;
    }

    /** A igual prioridad la regla específica de empresa manda sobre la genérica. */
    public int especificidad() { return companyId != null ? 0 : 1; }

    public void habilitar()    { this.enabled = true; }
    public void deshabilitar() { this.enabled = false; }

    public UUID getId()                 { return id; }
    public UUID getCompanyId()          { return companyId; }
    public String getRail()             { return rail; }
    public PayoutRail railValue()       { return PayoutRail.valueOf(rail); }
    public String getProvider()         { return provider; }
    public PayoutProvider providerValue(){ return PayoutProvider.valueOf(provider); }
    public UUID getBankAccountId()      { return bankAccountId; }
    public BigDecimal getMinAmount()    { return minAmount; }
    public BigDecimal getMaxAmount()    { return maxAmount; }
    public int getPriority()            { return priority; }
    public boolean isEnabled()          { return enabled; }
    public OffsetDateTime getCreatedAt(){ return createdAt; }
}
