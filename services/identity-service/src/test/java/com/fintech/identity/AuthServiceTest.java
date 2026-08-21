package com.fintech.identity;

import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.DeviceTrackingResult;
import com.fintech.identity.application.IssuedSession;
import com.fintech.identity.application.LoginCommand;
import com.fintech.identity.application.LoginResult;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.port.out.CredentialRepository;
import com.fintech.identity.application.port.out.GeoLocationPort;
import com.fintech.identity.application.port.out.LoginEventPublisher;
import com.fintech.identity.application.port.out.MfaPendingRepository;
import com.fintech.identity.application.port.out.MfaRepository;
import com.fintech.identity.application.port.out.SessionTokenIssuer;
import com.fintech.identity.application.port.out.TokenClaims;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.application.service.AuthService;
import com.fintech.identity.application.service.DeviceTracker;
import com.fintech.identity.domain.AccountLockedException;
import com.fintech.identity.domain.AuthToken;
import com.fintech.identity.domain.CredentialNotFoundException;
import com.fintech.identity.domain.CredentialStatus;
import com.fintech.identity.domain.CredentialType;
import com.fintech.identity.domain.IdentityCredential;
import com.fintech.identity.domain.InvalidCredentialsException;
import com.fintech.identity.domain.TokenException;
import com.fintech.identity.domain.event.GeoLocation;
import com.fintech.identity.domain.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock CredentialRepository credentialRepository;
    @Mock TokenRepository tokenRepository;
    @Mock TokenPort tokenPort;
    @Mock PasswordEncoder passwordEncoder;
    @Mock MfaRepository mfaRepository;
    @Mock MfaPendingRepository mfaPendingRepository;
    @Mock GeoLocationPort geoLocationPort;
    @Mock LoginEventPublisher loginEventPublisher;
    @Mock SessionTokenIssuer sessionTokenIssuer;
    @Mock DeviceTracker deviceTracker;

    AuthService authService;
    AuthProperties properties;

    final UUID partyId = UUID.randomUUID();
    final String username = "user@test.com";
    final String rawPassword = "1234";
    final String encodedPassword = "$2a$10$fakehash";
    final LoginCommand command = new LoginCommand(username, rawPassword, "127.0.0.1", "test-agent", null, null);

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setMaxFailedAttempts(5);
        properties.setLockoutDurationMinutes(30);
        properties.setAccessTokenExpiryMinutes(15);
        properties.setRefreshTokenExpiryDays(7);

        authService = new AuthService(credentialRepository, tokenRepository,
                tokenPort, passwordEncoder, properties, mfaRepository, mfaPendingRepository,
                geoLocationPort, loginEventPublisher, sessionTokenIssuer, deviceTracker);
    }

    // ── Login ─────────────────────────────────────────────────────────────

    @Test
    void login_success_returnsTokenPair() {
        IdentityCredential credential = IdentityCredential.create(partyId, username, CredentialType.NIP, encodedPassword);
        TokenPair pair = new TokenPair("access.token.jwt", "refreshOpaque", 900L);
        IssuedSession session = new IssuedSession(pair, UUID.randomUUID().toString(), Instant.now().plusSeconds(900));

        given(geoLocationPort.resolve(any())).willReturn(GeoLocation.unknown());
        given(credentialRepository.findByUsername(username)).willReturn(Optional.of(credential));
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(true);
        given(mfaRepository.findByPartyId(partyId)).willReturn(Optional.empty());
        given(sessionTokenIssuer.issue(eq(partyId), any(), any(), any())).willReturn(session);
        given(credentialRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(loginEventPublisher).publish(any());

        LoginResult result = authService.login(command);

        assertThat(result).isInstanceOf(LoginResult.TokensIssued.class);
        TokenPair returned = ((LoginResult.TokensIssued) result).tokens();
        assertThat(returned.accessToken()).isEqualTo("access.token.jwt");
        assertThat(returned.refreshToken()).isEqualTo("refreshOpaque");
        assertThat(returned.expiresIn()).isEqualTo(900L);
        then(credentialRepository).should().save(credential);
    }

    @Test
    void login_credentialNotFound_throws() {
        given(geoLocationPort.resolve(any())).willReturn(GeoLocation.unknown());
        given(credentialRepository.findByUsername(username)).willReturn(Optional.empty());
        willDoNothing().given(loginEventPublisher).publish(any());

        assertThatThrownBy(() -> authService.login(command))
                .isInstanceOf(CredentialNotFoundException.class);
    }

    @Test
    void login_wrongPassword_throwsInvalidCredentials() {
        IdentityCredential credential = IdentityCredential.create(partyId, username, CredentialType.NIP, encodedPassword);
        given(geoLocationPort.resolve(any())).willReturn(GeoLocation.unknown());
        given(credentialRepository.findByUsername(username)).willReturn(Optional.of(credential));
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(false);
        given(credentialRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(loginEventPublisher).publish(any());

        assertThatThrownBy(() -> authService.login(command))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(credential.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void login_locksAccountAfterMaxAttempts() {
        IdentityCredential credential = IdentityCredential.create(partyId, username, CredentialType.NIP, encodedPassword);
        given(geoLocationPort.resolve(any())).willReturn(GeoLocation.unknown());
        given(credentialRepository.findByUsername(username)).willReturn(Optional.of(credential));
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(false);
        given(credentialRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(loginEventPublisher).publish(any());

        // 4 intentos fallidos — aún no bloqueado
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(command))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        // 5to intento — bloquea la cuenta
        assertThatThrownBy(() -> authService.login(command))
                .isInstanceOf(AccountLockedException.class);

        assertThat(credential.getStatus()).isEqualTo(CredentialStatus.LOCKED);
        assertThat(credential.getLockedUntil()).isNotNull();
    }

    @Test
    void login_lockedAccount_throwsImmediately() {
        IdentityCredential credential = IdentityCredential.create(partyId, username, CredentialType.NIP, encodedPassword);
        for (int i = 0; i < 5; i++) {
            credential.recordFailure(5, 30);
        }
        given(geoLocationPort.resolve(any())).willReturn(GeoLocation.unknown());
        given(credentialRepository.findByUsername(username)).willReturn(Optional.of(credential));
        willDoNothing().given(loginEventPublisher).publish(any());

        assertThatThrownBy(() -> authService.login(command))
                .isInstanceOf(AccountLockedException.class);

        then(passwordEncoder).shouldHaveNoInteractions();
    }

    @Test
    void login_resetsFailedAttemptsOnSuccess() {
        IdentityCredential credential = IdentityCredential.create(partyId, username, CredentialType.NIP, encodedPassword);
        credential.recordFailure(5, 30);
        TokenPair pair = new TokenPair("token", "refresh", 900L);
        IssuedSession session = new IssuedSession(pair, UUID.randomUUID().toString(), Instant.now().plusSeconds(900));

        given(geoLocationPort.resolve(any())).willReturn(GeoLocation.unknown());
        given(credentialRepository.findByUsername(username)).willReturn(Optional.of(credential));
        given(passwordEncoder.matches(rawPassword, encodedPassword)).willReturn(true);
        given(mfaRepository.findByPartyId(partyId)).willReturn(Optional.empty());
        given(sessionTokenIssuer.issue(any(), any(), any(), any())).willReturn(session);
        given(credentialRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(loginEventPublisher).publish(any());

        authService.login(command);

        assertThat(credential.getFailedAttempts()).isZero();
        assertThat(credential.getStatus()).isEqualTo(CredentialStatus.ACTIVE);
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returnsNewPair() {
        String rawRefresh = "someOpaqueRefreshToken";
        AuthToken existingToken = AuthToken.create(partyId, null,
                sha256Hex(rawRefresh), UUID.randomUUID().toString(), 7, "127.0.0.1", null, Channel.MOBILE);
        TokenPair newPair = new TokenPair("new.access.jwt", "newRefresh", 900L);
        IssuedSession session = new IssuedSession(newPair, UUID.randomUUID().toString(), Instant.now().plusSeconds(900));

        given(tokenRepository.findByRefreshTokenHash(sha256Hex(rawRefresh)))
                .willReturn(Optional.of(existingToken));
        given(sessionTokenIssuer.issue(eq(partyId), any(), any(), any())).willReturn(session);
        given(tokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        TokenPair pair = authService.refresh(rawRefresh);

        assertThat(pair.accessToken()).isEqualTo("new.access.jwt");
        assertThat(existingToken.isRevoked()).isTrue();
    }

    @Test
    void refresh_revokedToken_throws() {
        String rawRefresh = "revokedToken";
        AuthToken revokedToken = AuthToken.create(partyId, null,
                sha256Hex(rawRefresh), UUID.randomUUID().toString(), 7, "127.0.0.1", null, Channel.MOBILE);
        revokedToken.revoke();
        given(tokenRepository.findByRefreshTokenHash(sha256Hex(rawRefresh)))
                .willReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> authService.refresh(rawRefresh))
                .isInstanceOf(TokenException.class);
    }

    @Test
    void refresh_notFound_throws() {
        given(tokenRepository.findByRefreshTokenHash(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("unknownToken"))
                .isInstanceOf(TokenException.class);
    }

    // ── Logout ────────────────────────────────────────────────────────────

    @Test
    void logout_revokesAllSessions() {
        authService.logout(partyId);

        then(tokenRepository).should().revokeAllByPartyId(partyId);
    }

    // ── Validate ──────────────────────────────────────────────────────────

    @Test
    void validate_validToken_returnsClaims() {
        String jti = UUID.randomUUID().toString();
        TokenClaims claims = new TokenClaims(partyId.toString(), jti, List.of("CUSTOMER"), null, Channel.MOBILE);

        given(tokenPort.validateToken("valid.jwt")).willReturn(Optional.of(claims));
        given(tokenRepository.isTokenActive(jti)).willReturn(true);

        var result = authService.validate("valid.jwt");

        assertThat(result.partyId()).isEqualTo(partyId);
        assertThat(result.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void validate_revokedToken_throws() {
        String jti = UUID.randomUUID().toString();
        TokenClaims claims = new TokenClaims(partyId.toString(), jti, List.of("CUSTOMER"), null, Channel.MOBILE);

        given(tokenPort.validateToken("revoked.jwt")).willReturn(Optional.of(claims));
        given(tokenRepository.isTokenActive(jti)).willReturn(false);

        assertThatThrownBy(() -> authService.validate("revoked.jwt"))
                .isInstanceOf(TokenException.class);
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private String sha256Hex(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
