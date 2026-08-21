package com.fintech.scoring.infrastructure.adapter.out.circulo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CdcPersonaRespuesta(
        String apellidoPaterno,
        String apellidoMaterno,
        String apellidoAdicional,
        String nombres,
        String fechaNacimiento,
        @JsonProperty("RFC") String rfc,
        @JsonProperty("CURP") String curp,
        String numeroSeguridadSocial,
        String nacionalidad,
        Integer residencia,
        String estadoCivil,
        String sexo,
        String claveElectorIFE,
        Integer numeroDependientes,
        String fechaDefuncion
) {}
