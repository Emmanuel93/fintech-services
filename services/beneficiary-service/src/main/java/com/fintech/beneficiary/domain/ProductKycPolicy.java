package com.fintech.beneficiary.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Lo que el catálogo dice del KYC de un producto, guardado de este lado.
 *
 * <p>Proyección de sólo lectura alimentada por {@code product-catalog.product-activated}: una fila
 * por producto, sobrescrita en cada activación. No se versiona porque no interesa el historial —
 * lo que aplicó a una colocación concreta queda en su propio veredicto de identidad, con autor y
 * fecha.
 *
 * <p>Existe para no llamar al catálogo en línea. Resolver una identidad no puede depender de que
 * otro servicio conteste en ese instante: si el catálogo está caído, la política vigente es la
 * última que publicó, no un error.
 */
@Entity
@Table(name = "product_kyc_policies", schema = "beneficiary")
public class ProductKycPolicy {

    @Id
    @Column(name = "product_code", length = 50, nullable = false)
    private String productCode;

    @Column(name = "product_version", nullable = false)
    private int productVersion;

    @Column(name = "kyc_required", nullable = false)
    private boolean kycRequired;

    @Column(name = "aml_required", nullable = false)
    private boolean amlRequired;

    @Column(name = "automation_enabled", nullable = false)
    private boolean automationEnabled;

    @Column(name = "provider", length = 30, nullable = false)
    private String provider;

    @Column(name = "manual_fallback_allowed", nullable = false)
    private boolean manualFallbackAllowed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductKycPolicy() { }

    private ProductKycPolicy(String productCode, int productVersion, KycPolicy policy) {
        this.productCode           = productCode;
        this.productVersion        = productVersion;
        this.kycRequired           = policy.kycRequired();
        this.amlRequired           = policy.amlRequired();
        this.automationEnabled     = policy.automationEnabled();
        this.provider              = policy.hasProvider() ? policy.provider() : KycPolicy.PROVIDER_NONE;
        this.manualFallbackAllowed = policy.manualFallbackAllowed();
        this.updatedAt             = Instant.now();
    }

    public static ProductKycPolicy of(String productCode, int productVersion, KycPolicy policy) {
        return new ProductKycPolicy(productCode, productVersion, policy);
    }

    /** Sobrescribe con lo último que publicó el catálogo. */
    public void refresh(int productVersion, KycPolicy policy) {
        this.productVersion        = productVersion;
        this.kycRequired           = policy.kycRequired();
        this.amlRequired           = policy.amlRequired();
        this.automationEnabled     = policy.automationEnabled();
        this.provider              = policy.hasProvider() ? policy.provider() : KycPolicy.PROVIDER_NONE;
        this.manualFallbackAllowed = policy.manualFallbackAllowed();
        this.updatedAt             = Instant.now();
    }

    public KycPolicy toPolicy() {
        return new KycPolicy(kycRequired, amlRequired, automationEnabled, provider, manualFallbackAllowed);
    }

    public String getProductCode()    { return productCode; }
    public int getProductVersion()    { return productVersion; }
    public Instant getUpdatedAt()     { return updatedAt; }
}
