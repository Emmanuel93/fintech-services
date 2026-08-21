package com.fintech.stp.infrastructure.adapter.out.http.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** {@code {"resultado": {"id": ..., "descripcionError": ...}}} */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistraOrdenPagoResponse(Resultado resultado) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resultado(Long id, String descripcionError) {}
}
