package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Alta de una unidad. {@code parentUnitId} nulo => unidad raíz (solo válido para el nivel de
 * profundidad 0). El {@code createdBy} no viaja en el cuerpo: se toma del empleado autenticado.
 */
public record CreateOrgUnitRequest(
        @NotNull UUID levelId,
        UUID parentUnitId,
        @NotBlank String code,
        @NotBlank String name,
        UUID partyRef) {}
