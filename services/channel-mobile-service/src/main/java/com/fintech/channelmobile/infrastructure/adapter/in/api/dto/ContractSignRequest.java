package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Firma del contrato: CLABE de desembolso (18 dígitos) + prueba de firma (OTP/biométrica). */
public record ContractSignRequest(
        @NotBlank @Pattern(regexp = "\\d{18}", message = "La CLABE debe tener 18 dígitos") String clabeAccount,
        @NotBlank String signatureProof,
        String documentRef
) {}
