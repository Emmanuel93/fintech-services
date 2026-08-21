package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

public record KycSubmitResponse(
        boolean success,
        String folioKyc
) {}
