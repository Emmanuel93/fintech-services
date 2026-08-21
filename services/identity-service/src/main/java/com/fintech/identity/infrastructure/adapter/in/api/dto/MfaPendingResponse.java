package com.fintech.identity.infrastructure.adapter.in.api.dto;

public record MfaPendingResponse(
        boolean mfaRequired,
        String mfaToken
) {
    public static MfaPendingResponse of(String mfaToken) {
        return new MfaPendingResponse(true, mfaToken);
    }
}
