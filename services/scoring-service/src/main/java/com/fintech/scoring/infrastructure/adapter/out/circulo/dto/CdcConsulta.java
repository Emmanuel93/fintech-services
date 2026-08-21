package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

import java.math.BigDecimal;

public record CdcConsulta(
        String fechaConsulta,
        String claveOtorgante,
        String nombreOtorgante,
        String direccionOtorgante,
        String telefonoOtorgante,
        String tipoCredito,
        String claveUnidadMonetaria,
        BigDecimal importeCredito,
        String tipoResponsabilidad,
        String idDomicilio,
        String servicios
) {}
