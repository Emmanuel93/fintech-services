package com.fintech.identity.application.service;

import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.IssuedSession;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.port.out.SessionTokenIssuer;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.domain.AuthToken;
import com.fintech.identity.domain.Channel;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implementación del puerto {@link SessionTokenIssuer}.
 * Centraliza la lógica de emisión de pares de tokens (access + refresh)
 * para que AuthService, MfaService y StaffAuthService no se acoplen entre sí.
 */
@Service
class SessionTokenService implements SessionTokenIssuer {

    private static final List<String> CUSTOMER_ROLES = List.of("CUSTOMER");

    private final TokenPort tokenPort;
    private final TokenRepository tokenRepository;
    private final AuthProperties properties;

    SessionTokenService(TokenPort tokenPort,
                        TokenRepository tokenRepository,
                        AuthProperties properties) {
        this.tokenPort = tokenPort;
        this.tokenRepository = tokenRepository;
        this.properties = properties;
    }

    @Override
    public IssuedSession issue(UUID partyId, String deviceId, String ipAddress, String userAgent) {
        return issueSession(partyId, deviceId, CUSTOMER_ROLES, ipAddress, userAgent, Channel.MOBILE);
    }

    @Override
    public IssuedSession issueForStaff(UUID staffUserId, List<String> roles,
                                       String ipAddress, String userAgent) {
        // El backoffice es una consola web: no hay deviceId que registrar.
        return issueSession(staffUserId, null, roles, ipAddress, userAgent, Channel.BACKOFFICE);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private IssuedSession issueSession(UUID subject, String deviceId, List<String> roles,
                                       String ipAddress, String userAgent, Channel channel) {
        String accessToken = tokenPort.generateAccessToken(subject, roles, deviceId, channel);
        String rawRefresh = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String refreshHash = TokenHashing.sha256Hex(rawRefresh);
        String jti = tokenPort.extractJti(accessToken);
        long expirySeconds = (long) properties.getAccessTokenExpiryMinutes() * 60;
        Instant expiresAt = Instant.now().plusSeconds(expirySeconds);

        AuthToken token = AuthToken.create(subject, deviceId, refreshHash, jti,
                properties.getRefreshTokenExpiryDays(), ipAddress, userAgent, channel);
        tokenRepository.save(token);

        return new IssuedSession(new TokenPair(accessToken, rawRefresh, expirySeconds), jti, expiresAt);
    }

}
