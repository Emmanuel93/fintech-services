package com.fintech.stp.infrastructure.adapter.in.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * A credit as STP notifies it to the receiving participant ({@code abono}), with STP's field names. Only what the
 * connector uses is declared; the rest (CoDi folio, second beneficiary) is ignored instead of breaking the
 * deserialization when STP grows the payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StpCreditNotification(
        Long id,
        Integer fechaOperacion,
        Integer institucionOrdenante,
        Integer institucionBeneficiaria,
        String claveRastreo,
        BigDecimal monto,
        String nombreOrdenante,
        Integer tipoCuentaOrdenante,
        String cuentaOrdenante,
        String rfcCurpOrdenante,
        String nombreBeneficiario,
        Integer tipoCuentaBeneficiario,
        String cuentaBeneficiario,
        String rfcCurpBeneficiario,
        String conceptoPago,
        Long referenciaNumerica,
        String empresa,
        Integer tipoPago,
        Long tsLiquidacion) {
}
