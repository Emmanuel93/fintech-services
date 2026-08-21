package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

public record LoginResponse(
        boolean success,
        String token,
        String refreshToken,
        String userId,
        long expiresIn
) {
    public static LoginResponse of(String accessToken, String refreshToken, long expiresIn) {
        // userId se extrae del JWT en el cliente; aquí no lo parseamos por no duplicar lógica
        return new LoginResponse(true, accessToken, refreshToken, null, expiresIn);
    }
}
