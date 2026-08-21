package com.fintech.disbursement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Traduce el identificador de empresa del sistema emisor al {@code companyId} de este servicio.
 *
 * <p>Existe porque quien emite el evento puede llamarle distinto (un {@code tenantId}, un código de
 * producto, una unidad de negocio). Sin esta tabla habría que meter un {@code switch} con nombres de
 * clientes en el código — que es exactamente el acoplamiento del legado que se está eliminando.
 *
 * <p>DB-07: si no hay mapeo, la orden falla con {@code UNRESOLVED_COMPANY}. Nunca se adivina.
 */
@Entity
@Table(schema = "disbursement", name = "company_mappings")
public class CompanyMapping {

    @Id
    @Column(name = "company_mapping_id", nullable = false, updatable = false)
    private UUID companyMappingId;

    @Column(name = "source_system", nullable = false, updatable = false)
    private String sourceSystem;

    /** Clave del emisor. Cadena opaca: aquí no se interpreta. */
    @Column(name = "source_key", nullable = false, updatable = false)
    private String sourceKey;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CompanyMapping() {
    }

    public static CompanyMapping of(String sourceSystem, String sourceKey, UUID companyId) {
        CompanyMapping mapping = new CompanyMapping();
        mapping.companyMappingId = UUID.randomUUID();
        mapping.sourceSystem = sourceSystem;
        mapping.sourceKey = sourceKey;
        mapping.companyId = companyId;
        mapping.enabled = true;
        mapping.createdAt = Instant.now();
        return mapping;
    }

    /**
     * Corregir a qué empresa apunta una clave del emisor. Es una operación normal — un mapeo mal
     * dado significa firmar con la llave equivocada — y por eso no puede resolverse ignorando en
     * silencio el valor nuevo.
     */
    public void reassign(UUID newCompanyId) {
        if (newCompanyId == null) {
            throw new IllegalArgumentException("companyId es obligatorio");
        }
        this.companyId = newCompanyId;
        this.enabled = true;
    }

    public void disable() { this.enabled = false; }

    public UUID getCompanyMappingId() { return companyMappingId; }
    public String getSourceSystem() { return sourceSystem; }
    public String getSourceKey() { return sourceKey; }
    public UUID getCompanyId() { return companyId; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
