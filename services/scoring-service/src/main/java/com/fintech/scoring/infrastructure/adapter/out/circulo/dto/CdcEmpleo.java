package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

import java.math.BigDecimal;

public record CdcEmpleo(
        String nombreEmpresa,
        String direccion,
        String coloniaPoblacion,
        String delegacionMunicipio,
        String ciudad,
        String estado,
        String CP,
        String numeroTelefono,
        String extension,
        String fax,
        String puesto,
        String fechaContratacion,
        String claveMoneda,
        BigDecimal salarioMensual,
        String fechaUltimoDiaEmpleo,
        String fechaVerificacionEmpleo
) {}
