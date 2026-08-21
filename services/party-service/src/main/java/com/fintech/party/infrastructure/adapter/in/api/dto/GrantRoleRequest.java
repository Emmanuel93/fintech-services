package com.fintech.party.infrastructure.adapter.in.api.dto;

import com.fintech.party.domain.PartyRoleType;
import jakarta.validation.constraints.NotNull;

public record GrantRoleRequest(@NotNull PartyRoleType roleType) {}
