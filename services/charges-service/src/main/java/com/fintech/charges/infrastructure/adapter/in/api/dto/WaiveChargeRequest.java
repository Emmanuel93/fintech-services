package com.fintech.charges.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

public record WaiveChargeRequest(
        @NotBlank String waivedBy
) {}
