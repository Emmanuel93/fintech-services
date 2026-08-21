package com.fintech.stp.infrastructure.adapter.out.http.dto;

import java.math.BigDecimal;

/**
 * Cuerpo de {@code PUT ordenPago/registra}. Nombres en español porque son los del contrato de STP:
 * este record es la frontera, y traducirlos aquí sería esconder el acoplamiento, no quitarlo.
 */
public record RegistraOrdenPagoRequest(
        String claveRastreo,
        String conceptoPago,
        String cuentaBeneficiario,
        String cuentaOrdenante,
        String empresa,
        Integer fechaOperacion,
        String firma,
        Integer institucionContraparte,
        Integer institucionOperante,
        BigDecimal monto,
        String nombreBeneficiario,
        String nombreOrdenante,
        Long referenciaNumerica,
        String rfcCurpBeneficiario,
        String rfcCurpOrdenante,
        Integer tipoCuentaBeneficiario,
        Integer tipoCuentaOrdenante,
        Integer tipoPago
) {}
