package com.fintech.stp.infrastructure.adapter.out.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConciliacionResponse(
        String estado,
        String mensaje,
        Integer total,
        List<Detalle> datos
) {

    /** Una orden tal como la reporta STP. Se mapean los 30 campos del contrato. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detalle(
            Long idEF,
            String claveRastreo,
            String claveRastreoDevolucion,
            String conceptoPago,
            String cuentaBeneficiario,
            String cuentaOrdenante,
            String empresa,
            String estado,
            Integer fechaOperacion,
            Integer institucionContraparte,
            Integer institucionOperante,
            Integer medioEntrega,
            BigDecimal monto,
            String nombreBeneficiario,
            String nombreCep,
            String nombreOrdenante,
            Integer prioridad,
            Long referenciaNumerica,
            String rfcCep,
            String rfcCurpBeneficiario,
            String rfcCurpOrdenante,
            String sello,
            Short tipoCuentaBeneficiario,
            Short tipoCuentaOrdenante,
            Short tipoPago,
            String topologia,
            Long tsCaptura,
            Long tsLiquidacion,
            Integer causaDevolucion,
            String urlCEP
    ) {}
}
