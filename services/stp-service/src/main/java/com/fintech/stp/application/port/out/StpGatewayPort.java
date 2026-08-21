package com.fintech.stp.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * ACL de salida hacia STP. La única frontera del servicio con el proveedor.
 *
 * <p>Dos implementaciones: {@code RestClientStpGateway} (real) y {@code StubStpGateway} (ambientes
 * bajos). Se eligen con {@code fintech.stp.gateway.mode}.
 *
 * <p>Los tipos de esta interfaz son de dominio, no del protocolo de STP: quien la implementa
 * traduce. Así el servicio de aplicación no conoce {@code RespuestaBanxicoDTO} ni el JSON de STP.
 */
public interface StpGatewayPort {

    /**
     * Registra una orden de pago. La firma ya viene calculada: el gateway no firma, sólo transporta.
     *
     * @return el identificador que devolvió STP — positivo si aceptó, código de error si no
     */
    RegistrationResult registerPaymentOrder(PaymentOrderRequest request);

    /**
     * Consulta la conciliación de un día. Es lo que sustituye a los webhooks.
     *
     * @param tipoOrden {@code "E"} enviadas · {@code "R"} recibidas
     * @param page      página base 1
     */
    ReconciliationPage queryReconciliation(String stpEmpresa, String tipoOrden, LocalDate businessDate,
                                           int page, String signature);

    /** Lo que se manda a registrar. Espejo del contrato de STP, sin nada de nuestro dominio. */
    record PaymentOrderRequest(
            String claveRastreo,
            String empresa,
            LocalDate fechaOperacion,
            Integer institucionOperante,
            Integer institucionContraparte,
            BigDecimal monto,
            Integer tipoPago,
            Integer tipoCuentaOrdenante,
            String nombreOrdenante,
            String cuentaOrdenante,
            String rfcCurpOrdenante,
            Integer tipoCuentaBeneficiario,
            String nombreBeneficiario,
            String cuentaBeneficiario,
            String rfcCurpBeneficiario,
            String conceptoPago,
            Long referenciaNumerica,
            String firma
    ) {}

    /** Lo que STP contestó al registrar. */
    record RegistrationResult(
            long stpResponseId,
            String descripcionError
    ) {}

    /** Una página de conciliación, ya traducida. */
    record ReconciliationPage(
            String estado,
            String mensaje,
            int total,
            List<ReconciliationEntry> entries
    ) {}

    /**
     * Una orden tal como la reporta STP. Sólo los campos que este servicio usa; lo demás viaja en
     * {@code rawPayload} como evidencia (SO-01).
     */
    record ReconciliationEntry(
            String claveRastreo,
            String estado,
            String causaDevolucion,
            String urlCEP,
            String nombreCep,
            String rfcCep,
            String sello,
            Long tsLiquidacion,
            Long tsCaptura,
            BigDecimal monto,
            String cuentaBeneficiario,
            /** Evidencia cruda completa, tal cual llegó. Es lo que se archiva (SO-01). */
            String rawPayload,
            /**
             * Lo mismo <strong>sin el campo {@code sello}</strong>: es lo único contra lo que tiene
             * sentido verificar una firma. Verificar contra un payload que contiene su propia firma
             * es imposible por construcción, y era el defecto de la versión anterior.
             */
            String signedPayload
    ) {}
}
