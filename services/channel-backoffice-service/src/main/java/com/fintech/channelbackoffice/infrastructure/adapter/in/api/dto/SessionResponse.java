package com.fintech.channelbackoffice.infrastructure.adapter.in.api.dto;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;

import java.util.List;

/**
 * Sesión tal como la consume la consola.
 *
 * <p>La traducción de {@code staffUserId} a {@code id} es deliberada: el contrato del BFF lo fija
 * el front, y el front piensa en "el usuario de la sesión", no en el identificador interno de
 * identity-service. Es exactamente el trabajo de un BFF.
 */
public record SessionResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String channel,
        StaffUser user
) {
    public record StaffUser(
            String id,
            String email,
            String fullName,
            String employeeType,
            List<String> roles
    ) {}

    public static SessionResponse from(IdentityClient.StaffSessionResponse s) {
        return new SessionResponse(
                s.accessToken(), s.refreshToken(), s.expiresIn(), s.channel(), user(s.user()));
    }

    public static StaffUser user(IdentityClient.StaffProfileResponse p) {
        return new StaffUser(
                p.staffUserId().toString(), p.email(), p.fullName(), p.employeeType(), p.roles());
    }
}
