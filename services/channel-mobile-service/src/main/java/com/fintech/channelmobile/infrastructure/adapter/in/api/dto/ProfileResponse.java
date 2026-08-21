package com.fintech.channelmobile.infrastructure.adapter.in.api.dto;

/**
 * Perfil en el shape que consume la app (fa_profile.ProfileDto). El BFF adapta la
 * respuesta de party-service (firstName/lastName1/status/createdAt) a este contrato.
 * phone/email/state hoy no los expone party → van vacíos (la app es tolerante).
 */
public record ProfileResponse(
        String id,
        String fullName,
        String phone,
        String email,
        String curp,
        String rfc,
        String kycStatus,
        String memberSince,
        String dateOfBirth,
        String state
) {}
