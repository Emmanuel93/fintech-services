package com.fintech.scoring.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "circulo_credits", schema = "scoring")
public class CirculoCredit {

    @Id
    private UUID creditId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false)
    private CirculoReport report;

    private String claveOtorgante;
    private String nombreOtorgante;
    private String cuentaActual;
    @Column(length = 1) private String tipoResponsabilidad;
    @Column(length = 1) private String tipoCuenta;
    @Column(length = 2) private String tipoCredito;
    @Column(length = 2) private String moneda;
    private BigDecimal valorActivoValuacion;
    private Integer numeroPagos;
    @Column(length = 1) private String frecuenciaPagos;
    private BigDecimal montoPagar;
    private LocalDate fechaApertura;
    private LocalDate fechaUltimoPago;
    private LocalDate fechaUltimaCompra;
    private LocalDate fechaCierre;
    private LocalDate fechaReporte;
    private LocalDate ultimaFechaSaldoCero;
    private BigDecimal creditoMaximo;
    private BigDecimal saldoActual;
    private BigDecimal limiteCredito;
    private BigDecimal saldoVencido;
    private Integer numeroPagosVencidos;
    @Column(length = 2) private String pagoActual;
    @Column(columnDefinition = "TEXT") private String historicoPagos;
    private LocalDate fechaRecienteHistorico;
    private LocalDate fechaAntiguaHistorico;
    @Column(length = 2) private String clavePrevencion;
    private Integer totalPagosReportados;
    private BigDecimal peorAtraso;
    private LocalDate fechaPeorAtraso;
    private BigDecimal saldoVencidoPeorAtraso;
    private BigDecimal montoUltimoPago;
    private Integer registroImpugnado;
    private LocalDate fechaActualizacion;

    protected CirculoCredit() {}

    public CirculoCredit(UUID creditId, CirculoReport report,
                         String claveOtorgante, String nombreOtorgante, String cuentaActual,
                         String tipoResponsabilidad, String tipoCuenta, String tipoCredito,
                         String moneda, BigDecimal valorActivoValuacion,
                         Integer numeroPagos, String frecuenciaPagos, BigDecimal montoPagar,
                         LocalDate fechaApertura, LocalDate fechaUltimoPago, LocalDate fechaUltimaCompra,
                         LocalDate fechaCierre, LocalDate fechaReporte, LocalDate ultimaFechaSaldoCero,
                         BigDecimal creditoMaximo, BigDecimal saldoActual, BigDecimal limiteCredito,
                         BigDecimal saldoVencido, Integer numeroPagosVencidos, String pagoActual,
                         String historicoPagos, LocalDate fechaRecienteHistorico, LocalDate fechaAntiguaHistorico,
                         String clavePrevencion, Integer totalPagosReportados,
                         BigDecimal peorAtraso, LocalDate fechaPeorAtraso, BigDecimal saldoVencidoPeorAtraso,
                         BigDecimal montoUltimoPago, Integer registroImpugnado, LocalDate fechaActualizacion) {
        this.creditId = creditId;
        this.report = report;
        this.claveOtorgante = claveOtorgante;
        this.nombreOtorgante = nombreOtorgante;
        this.cuentaActual = cuentaActual;
        this.tipoResponsabilidad = tipoResponsabilidad;
        this.tipoCuenta = tipoCuenta;
        this.tipoCredito = tipoCredito;
        this.moneda = moneda;
        this.valorActivoValuacion = valorActivoValuacion;
        this.numeroPagos = numeroPagos;
        this.frecuenciaPagos = frecuenciaPagos;
        this.montoPagar = montoPagar;
        this.fechaApertura = fechaApertura;
        this.fechaUltimoPago = fechaUltimoPago;
        this.fechaUltimaCompra = fechaUltimaCompra;
        this.fechaCierre = fechaCierre;
        this.fechaReporte = fechaReporte;
        this.ultimaFechaSaldoCero = ultimaFechaSaldoCero;
        this.creditoMaximo = creditoMaximo;
        this.saldoActual = saldoActual;
        this.limiteCredito = limiteCredito;
        this.saldoVencido = saldoVencido;
        this.numeroPagosVencidos = numeroPagosVencidos;
        this.pagoActual = pagoActual;
        this.historicoPagos = historicoPagos;
        this.fechaRecienteHistorico = fechaRecienteHistorico;
        this.fechaAntiguaHistorico = fechaAntiguaHistorico;
        this.clavePrevencion = clavePrevencion;
        this.totalPagosReportados = totalPagosReportados;
        this.peorAtraso = peorAtraso;
        this.fechaPeorAtraso = fechaPeorAtraso;
        this.saldoVencidoPeorAtraso = saldoVencidoPeorAtraso;
        this.montoUltimoPago = montoUltimoPago;
        this.registroImpugnado = registroImpugnado;
        this.fechaActualizacion = fechaActualizacion;
    }

    public UUID getCreditId()                   { return creditId; }
    public String getNombreOtorgante()          { return nombreOtorgante; }
    public String getTipoCredito()              { return tipoCredito; }
    public BigDecimal getSaldoActual()          { return saldoActual; }
    public BigDecimal getSaldoVencido()         { return saldoVencido; }
    public BigDecimal getLimiteCredito()        { return limiteCredito; }
    public BigDecimal getPeorAtraso()           { return peorAtraso; }
    public BigDecimal getSaldoVencidoPeorAtraso(){ return saldoVencidoPeorAtraso; }
    public Integer getNumeroPagosVencidos()     { return numeroPagosVencidos; }

    // Estos cuatro se persistían desde el alta del reporte y no se leían desde ningún lado: el
    // motor de scoring sólo miraba mora, FICO y conteo de créditos. Sin ellos no se puede medir
    // ni cuándo fue el tropiezo, ni cuánto historial tiene, ni cuánto paga al mes, ni si el
    // otorgante marcó el crédito — cuatro señales que el buró ya venía entregando.
    public LocalDate getFechaPeorAtraso()       { return fechaPeorAtraso; }
    public LocalDate getFechaApertura()         { return fechaApertura; }
    public BigDecimal getMontoPagar()           { return montoPagar; }
    public String getClavePrevencion()          { return clavePrevencion; }
}
