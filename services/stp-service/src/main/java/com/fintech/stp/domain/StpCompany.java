package com.fintech.stp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Una empresa con contrato propio ante STP. Es el tenant de este servicio.
 *
 * <p>Lo que en el legado eran constantes en el código —el nombre de empresa que viaja en la cadena
 * firmada, la institución operante, el prefijo de la clave de rastreo— aquí son columnas.
 *
 * <p>Inv: SC-01 {@code stpEmpresa} es inmutable — va dentro de la cadena firmada, cambiarlo
 * invalidaría las firmas históricas.
 */
@Entity
@Table(schema = "stp", name = "companies")
public class StpCompany {

    @Id
    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    /** Identificador interno legible. */
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    /** El literal EXACTO que STP espera en la posición 2 de la cadena original. */
    @Column(name = "stp_empresa", nullable = false, updatable = false)
    private String stpEmpresa;

    @Column(name = "institucion_operante", nullable = false)
    private Integer institucionOperante;

    /** Prefijo de la clave de rastreo. En el legado era una constante de dos letras. */
    @Column(name = "tracking_prefix", nullable = false)
    private String trackingPrefix;

    @Column(name = "clabe_bank_code", nullable = false)
    private String clabeBankCode;

    @Column(name = "clabe_plaza_code", nullable = false)
    private String clabePlazaCode;

    @Column(name = "clabe_client_prefix")
    private String clabeClientPrefix;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StpCompany() {
    }

    public static StpCompany create(String code, String stpEmpresa, Integer institucionOperante,
                                    String trackingPrefix, String clabeBankCode, String clabePlazaCode,
                                    String clabeClientPrefix) {
        StpCompany company = new StpCompany();
        company.companyId = UUID.randomUUID();
        company.code = requireText(code, "code");
        company.stpEmpresa = requireText(stpEmpresa, "stpEmpresa");
        company.institucionOperante = institucionOperante;
        company.trackingPrefix = requireText(trackingPrefix, "trackingPrefix");
        company.clabeBankCode = clabeBankCode != null ? clabeBankCode : "646";
        company.clabePlazaCode = clabePlazaCode != null ? clabePlazaCode : "180";
        company.clabeClientPrefix = clabeClientPrefix;
        company.status = CompanyStatus.ACTIVE.name();
        company.createdAt = Instant.now();
        return company;
    }

    public void suspend() { this.status = CompanyStatus.SUSPENDED.name(); }
    public void reactivate() { this.status = CompanyStatus.ACTIVE.name(); }
    public void retire() { this.status = CompanyStatus.RETIRED.name(); }

    public boolean isActive() { return CompanyStatus.ACTIVE.name().equals(status); }

    public UUID getCompanyId() { return companyId; }
    public String getCode() { return code; }
    public String getStpEmpresa() { return stpEmpresa; }
    public Integer getInstitucionOperante() { return institucionOperante; }
    public String getTrackingPrefix() { return trackingPrefix; }
    public String getClabeBankCode() { return clabeBankCode; }
    public String getClabePlazaCode() { return clabePlazaCode; }
    public String getClabeClientPrefix() { return clabeClientPrefix; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("El campo '" + field + "' es obligatorio");
        }
        return value;
    }
}
