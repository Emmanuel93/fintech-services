package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.Channel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TokenPort {

    /**
     * @param subject {@code partyId} en canal MOBILE, {@code staffUserId} en canal BACKOFFICE
     */
    String generateAccessToken(UUID subject, List<String> roles, String deviceId, Channel channel);

    Optional<TokenClaims> validateToken(String token);

    String extractJti(String token);
}
