package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

public record CdcDomicilioPeticion(
        String direccion,
        String coloniaPoblacion,
        String delegacionMunicipio,
        String ciudad,
        String estado,
        String CP
) {}
