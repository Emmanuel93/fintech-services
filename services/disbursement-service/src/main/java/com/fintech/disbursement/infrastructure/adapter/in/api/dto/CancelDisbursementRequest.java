package com.fintech.disbursement.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cancelar sin motivo no es cancelar, es perder la trazabilidad. */
public record CancelDisbursementRequest(
        @NotBlank(message = "El motivo es obligatorio")
        @Size(max = 500)
        String reason
) {}
