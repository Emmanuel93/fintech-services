package com.fintech.stp.infrastructure.adapter.out.http.stub;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Lo que el stub recibió, tal cual. Es lo que le permite devolver el eco en la conciliación.
 *
 * <p>Se persiste en vez de guardarse en memoria para que el poller —que corre minutos después y
 * puede tocarle a otra réplica— vea lo mismo. La tabla queda vacía en producción.
 */
@Entity
@Table(schema = "stp", name = "stub_orders")
public class StubOrder {

    @Id
    @Column(name = "stub_order_id", nullable = false, updatable = false)
    private UUID stubOrderId;

    @Column(name = "clave_rastreo", nullable = false, updatable = false)
    private String claveRastreo;

    @Column(name = "empresa", nullable = false, updatable = false)
    private String empresa;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(name = "monto", nullable = false, updatable = false)
    private BigDecimal monto;

    @Column(name = "cuenta_ordenante", updatable = false)
    private String cuentaOrdenante;

    @Column(name = "nombre_ordenante", updatable = false)
    private String nombreOrdenante;

    @Column(name = "rfc_curp_ordenante", updatable = false)
    private String rfcCurpOrdenante;

    @Column(name = "cuenta_beneficiario", updatable = false)
    private String cuentaBeneficiario;

    @Column(name = "nombre_beneficiario", updatable = false)
    private String nombreBeneficiario;

    @Column(name = "rfc_curp_beneficiario", updatable = false)
    private String rfcCurpBeneficiario;

    @Column(name = "institucion_contraparte", updatable = false)
    private Integer institucionContraparte;

    @Column(name = "institucion_operante", updatable = false)
    private Integer institucionOperante;

    @Column(name = "concepto_pago", updatable = false)
    private String conceptoPago;

    @Column(name = "referencia_numerica", updatable = false)
    private Long referenciaNumerica;

    @Column(name = "tipo_pago", updatable = false)
    private Integer tipoPago;

    @Column(name = "tipo_cuenta_beneficiario", updatable = false)
    private Integer tipoCuentaBeneficiario;

    @Column(name = "tipo_cuenta_ordenante", updatable = false)
    private Integer tipoCuentaOrdenante;

    /** La firma que recibimos. El stub la verifica, igual que haría STP. */
    @Column(name = "firma_recibida", updatable = false)
    private String firmaRecibida;

    @Column(name = "firma_valida")
    private Boolean firmaValida;

    @Column(name = "scenario", nullable = false, updatable = false)
    private String scenario;

    @Column(name = "stp_response_id", nullable = false, updatable = false)
    private Long stpResponseId;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected StubOrder() {
    }

    static StubOrder capture(com.fintech.stp.application.port.out.StpGatewayPort.PaymentOrderRequest request,
                             StubScenario scenario, long stpResponseId, Boolean firmaValida) {
        StubOrder order = new StubOrder();
        order.stubOrderId = UUID.randomUUID();
        order.claveRastreo = request.claveRastreo();
        order.empresa = request.empresa();
        order.businessDate = request.fechaOperacion();
        order.monto = request.monto();
        order.cuentaOrdenante = request.cuentaOrdenante();
        order.nombreOrdenante = request.nombreOrdenante();
        order.rfcCurpOrdenante = request.rfcCurpOrdenante();
        order.cuentaBeneficiario = request.cuentaBeneficiario();
        order.nombreBeneficiario = request.nombreBeneficiario();
        order.rfcCurpBeneficiario = request.rfcCurpBeneficiario();
        order.institucionContraparte = request.institucionContraparte();
        order.institucionOperante = request.institucionOperante();
        order.conceptoPago = request.conceptoPago();
        order.referenciaNumerica = request.referenciaNumerica();
        order.tipoPago = request.tipoPago();
        order.tipoCuentaBeneficiario = request.tipoCuentaBeneficiario();
        order.tipoCuentaOrdenante = request.tipoCuentaOrdenante();
        order.firmaRecibida = request.firma();
        order.firmaValida = firmaValida;
        order.scenario = scenario.name();
        order.stpResponseId = stpResponseId;
        order.receivedAt = Instant.now();
        return order;
    }

    public StubScenario scenario() { return StubScenario.valueOf(scenario); }

    public UUID getStubOrderId() { return stubOrderId; }
    public String getClaveRastreo() { return claveRastreo; }
    public String getEmpresa() { return empresa; }
    public LocalDate getBusinessDate() { return businessDate; }
    public BigDecimal getMonto() { return monto; }
    public String getCuentaOrdenante() { return cuentaOrdenante; }
    public String getNombreOrdenante() { return nombreOrdenante; }
    public String getRfcCurpOrdenante() { return rfcCurpOrdenante; }
    public String getCuentaBeneficiario() { return cuentaBeneficiario; }
    public String getNombreBeneficiario() { return nombreBeneficiario; }
    public String getRfcCurpBeneficiario() { return rfcCurpBeneficiario; }
    public Integer getInstitucionContraparte() { return institucionContraparte; }
    public Integer getInstitucionOperante() { return institucionOperante; }
    public String getConceptoPago() { return conceptoPago; }
    public Long getReferenciaNumerica() { return referenciaNumerica; }
    public Integer getTipoPago() { return tipoPago; }
    public Integer getTipoCuentaBeneficiario() { return tipoCuentaBeneficiario; }
    public Integer getTipoCuentaOrdenante() { return tipoCuentaOrdenante; }
    public String getFirmaRecibida() { return firmaRecibida; }
    public Boolean getFirmaValida() { return firmaValida; }
    public Long getStpResponseId() { return stpResponseId; }
    public Instant getReceivedAt() { return receivedAt; }
}
