package com.fintech.party.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Asignación de ejecutivo de cuenta. El nombre se guarda desnormalizado para el listado. */
public record AssignExecutiveRequest(
        @NotNull UUID executiveId,
        String executiveName
) {}
