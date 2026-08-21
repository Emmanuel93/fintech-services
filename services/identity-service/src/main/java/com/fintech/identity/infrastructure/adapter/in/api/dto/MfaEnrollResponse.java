package com.fintech.identity.infrastructure.adapter.in.api.dto;

public record MfaEnrollResponse(String totpSecret, String qrUri) {}
