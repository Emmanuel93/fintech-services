package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "circulo_employments", schema = "scoring")
public class CirculoEmployment {

    @Id private UUID employmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private CirculoReport report;

    private String nombreEmpresa;
    private String puesto;
    private BigDecimal salarioMensual;
    @Column(length = 2) private String moneda;
    private LocalDate fechaContratacion;
    private LocalDate fechaUltimoDia;
    private LocalDate fechaVerificacion;
    private String ciudad;
    @Column(length = 4) private String estado;

    protected CirculoEmployment() {}

    public CirculoEmployment(UUID employmentId, CirculoReport report,
                             String nombreEmpresa, String puesto, BigDecimal salarioMensual, String moneda,
                             LocalDate fechaContratacion, LocalDate fechaUltimoDia, LocalDate fechaVerificacion,
                             String ciudad, String estado) {
        this.employmentId = employmentId;
        this.report = report;
        this.nombreEmpresa = nombreEmpresa;
        this.puesto = puesto;
        this.salarioMensual = salarioMensual;
        this.moneda = moneda;
        this.fechaContratacion = fechaContratacion;
        this.fechaUltimoDia = fechaUltimoDia;
        this.fechaVerificacion = fechaVerificacion;
        this.ciudad = ciudad;
        this.estado = estado;
    }

    public UUID getEmploymentId()       { return employmentId; }
    public String getNombreEmpresa()    { return nombreEmpresa; }
    public BigDecimal getSalarioMensual(){ return salarioMensual; }

    // La fecha de último día es la que distingue el empleo vigente del anterior; sin ella,
    // «antigüedad laboral» puede acabar midiendo un trabajo que la persona ya dejó.
    public LocalDate getFechaContratacion() { return fechaContratacion; }
    public LocalDate getFechaUltimoDia()    { return fechaUltimoDia; }
}
