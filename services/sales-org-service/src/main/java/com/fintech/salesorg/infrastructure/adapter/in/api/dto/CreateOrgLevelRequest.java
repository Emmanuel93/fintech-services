package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Alta de un nivel. Si {@code depth} viene nulo se anexa al fondo de la escalera; si trae un valor,
 * se inserta en esa posición corriendo los niveles iguales o más profundos.
 */
public record CreateOrgLevelRequest(
        @NotBlank String code,
        @NotBlank String name,
        @PositiveOrZero Integer depth) {}
