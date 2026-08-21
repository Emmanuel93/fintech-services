package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CdcDomicilioRespuesta(
        String direccion,
        String coloniaPoblacion,
        String delegacionMunicipio,
        String ciudad,
        String estado,
        @JsonProperty("CP") String cp,
        String fechaResidencia,
        String numeroTelefono,
        String tipoDomicilio,
        String tipoAsentamiento,
        String fechaRegistroDomicilio,
        Integer tipoAltaDomicilio,
        Integer numeroOtorgantesCarga,
        Integer numeroOtorgantesConsulta,
        String idDomicilio
) {}
