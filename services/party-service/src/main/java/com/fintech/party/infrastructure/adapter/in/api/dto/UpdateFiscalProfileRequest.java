package com.fintech.party.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Perfil fiscal CFDI 4.0 del party (receptor de la factura). */
public record UpdateFiscalProfileRequest(
        @NotBlank @Size(max = 300) String taxName,
        @NotBlank @Pattern(regexp = "\\d{3}", message = "taxRegime debe ser el código SAT de 3 dígitos (601, 612, ...)")
        String taxRegime,
        @NotBlank @Pattern(regexp = "\\d{5}", message = "taxZipCode debe ser un CP de 5 dígitos")
        String taxZipCode,
        @NotBlank @Size(max = 10) String cfdiUse
) {}
