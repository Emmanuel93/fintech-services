package com.fintech.salesorg.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Alta de una unidad. {@code parentUnitId} nulo => unidad raíz (solo válido para el nivel de
 * profundidad 0). El {@code createdBy} no viaja en el cuerpo: se toma del empleado autenticado.
 *
 * <p>{@code placementPolicy} declares the unit's placement policy in the same call: a branch is opened with the limits
 * its distributors will place under, instead of being left to inherit by omission.
 */
public record CreateOrgUnitRequest(
        @NotNull UUID levelId,
        UUID parentUnitId,
        @NotBlank String code,
        @NotBlank String name,
        UUID partyRef,
        PlacementPolicyRequest placementPolicy) {

    public CreateOrgUnitRequest(UUID levelId, UUID parentUnitId, String code, String name, UUID partyRef) {
        this(levelId, parentUnitId, code, name, partyRef, null);
    }
}
