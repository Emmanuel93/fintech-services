package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

public record OcrExtractResponse(
        boolean success,
        String nombres,
        String apellidoPaterno,
        String apellidoMaterno,
        String curp,
        String fechaNacimiento,
        String domicilio,
        String clave,
        String vigencia,
        String genero,
        String estadoNacimiento,
        String folio
) {}
