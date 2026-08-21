package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.AuthToken;

import java.util.Optional;
import java.util.UUID;

public interface TokenRepository {

    /** Returns true if the JTI key exists in Redis (token issued and not revoked). */
    boolean isTokenActive(String tokenId);

    Optional<AuthToken> findByRefreshTokenHash(String refreshTokenHash);

    AuthToken save(AuthToken token);

    void revokeAllByPartyId(UUID partyId);
}
