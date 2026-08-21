package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "circulo_inquiries", schema = "scoring")
public class CirculoInquiry {

    @Id private UUID inquiryId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private CirculoReport report;

    private LocalDate fechaConsulta;
    private String nombreOtorgante;
    @Column(length = 2) private String tipoCredito;
    @Column(length = 2) private String moneda;
    private BigDecimal importeCredito;
    @Column(length = 1) private String tipoResponsabilidad;

    protected CirculoInquiry() {}

    public CirculoInquiry(UUID inquiryId, CirculoReport report,
                          LocalDate fechaConsulta, String nombreOtorgante,
                          String tipoCredito, String moneda,
                          BigDecimal importeCredito, String tipoResponsabilidad) {
        this.inquiryId = inquiryId;
        this.report = report;
        this.fechaConsulta = fechaConsulta;
        this.nombreOtorgante = nombreOtorgante;
        this.tipoCredito = tipoCredito;
        this.moneda = moneda;
        this.importeCredito = importeCredito;
        this.tipoResponsabilidad = tipoResponsabilidad;
    }

    public UUID getInquiryId()             { return inquiryId; }
    public LocalDate getFechaConsulta()    { return fechaConsulta; }
    public String getNombreOtorgante()     { return nombreOtorgante; }
    public String getTipoCredito()         { return tipoCredito; }
    public BigDecimal getImporteCredito()  { return importeCredito; }
}
