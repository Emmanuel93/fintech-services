package com.fintech.identity;

import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.IssuedSession;
import com.fintech.identity.application.StaffLoginCommand;
import com.fintech.identity.application.StaffSession;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.port.out.SessionTokenIssuer;
import com.fintech.identity.application.port.out.StaffUserRepository;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.application.service.StaffAuthService;
import com.fintech.identity.domain.AccountLockedException;
import com.fintech.identity.domain.AuthToken;
import com.fintech.identity.domain.Channel;
import com.fintech.identity.domain.ChannelMismatchException;
import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.InvalidCredentialsException;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffUser;
import com.fintech.identity.domain.TokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class StaffAuthServiceTest {

    @Mock StaffUserRepository staffUserRepository;
    @Mock TokenRepository tokenRepository;
    @Mock SessionTokenIssuer sessionTokenIssuer;
    @Mock PasswordEncoder passwordEncoder;

    StaffAuthService service;
    AuthProperties properties;

    final String email = "ana.torres@kredius.mx";
    final String rawPassword = "Backoffice#2026";
    final String hash = "$2a$10$fakehash";

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setMaxFailedAttempts(3);
        properties.setLockoutDurationMinutes(30);
        properties.setAccessTokenExpiryMinutes(15);
        properties.setRefreshTokenExpiryDays(7);

        service = new StaffAuthService(staffUserRepository, tokenRepository,
                sessionTokenIssuer, passwordEncoder, properties);
    }

    private StaffUser executive() {
        return StaffUser.create(email, "Ana Torres", null, EmployeeType.INTERNO, null,
                Set.of(StaffRole.EXECUTIVE), hash);
    }

    private StaffLoginCommand command() {
        return new StaffLoginCommand(email, rawPassword, "127.0.0.1", "test-agent", null);
    }

    private IssuedSession anySession() {
        return new IssuedSession(
                new TokenPair("staff.access.jwt", "staffRefresh", 900L),
                UUID.randomUUID().toString(),
                Instant.now().plusSeconds(900));
    }

    // ── Login ─────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_issuesBackofficeSessionWithStaffRoles() {
        StaffUser user = executive();
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(rawPassword, hash)).willReturn(true);
        given(sessionTokenIssuer.issueForStaff(eq(user.getStaffUserId()), eq(List.of("EXECUTIVE")), any(), any()))
                .willReturn(anySession());

        StaffSession session = service.login(command());

        assertThat(session.tokens().accessToken()).isEqualTo("staff.access.jwt");
        assertThat(session.profile().email()).isEqualTo(email);
        assertThat(session.profile().roles()).containsExactly("EXECUTIVE");
        then(staffUserRepository).should().save(user);
        assertThat(user.getLastLoginIp()).isEqualTo("127.0.0.1");
    }

    @Test
    void login_normalizesEmailBeforeLookup() {
        StaffUser user = executive();
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(any(), any())).willReturn(true);
        given(sessionTokenIssuer.issueForStaff(any(), any(), any(), any())).willReturn(anySession());

        service.login(new StaffLoginCommand("  Ana.Torres@Kredius.MX  ", rawPassword,
                "127.0.0.1", "test-agent", null));

        then(staffUserRepository).should().findByEmail(email);
    }

    @Test
    void login_unknownEmail_throwsInvalidCredentialsNotNotFound() {
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.empty());

        // No revelamos qué correos existen: mismo error que una contraseña incorrecta.
        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_wrongPassword_countsFailureAndThrows() {
        StaffUser user = executive();
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(rawPassword, hash)).willReturn(false);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getFailedAttempts()).isEqualTo(1);
        then(staffUserRepository).should().save(user);
    }

    @Test
    void login_lastAllowedFailure_locksAndThrowsLocked() {
        StaffUser user = executive();
        user.recordFailure(3, 30);
        user.recordFailure(3, 30);
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(rawPassword, hash)).willReturn(false);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(AccountLockedException.class);

        assertThat(user.isLocked()).isTrue();
    }

    @Test
    void login_lockedAccount_throwsBeforeCheckingPassword() {
        StaffUser user = executive();
        user.recordFailure(1, 30);
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(AccountLockedException.class);

        then(passwordEncoder).shouldHaveNoInteractions();
    }

    @Test
    void login_suspendedAccount_rejectsEvenWithRightPassword() {
        StaffUser user = executive();
        user.suspend();
        given(staffUserRepository.findByEmail(email)).willReturn(Optional.of(user));
        given(passwordEncoder.matches(rawPassword, hash)).willReturn(true);

        assertThatThrownBy(() -> service.login(command()))
                .isInstanceOf(InvalidCredentialsException.class);

        then(sessionTokenIssuer).shouldHaveNoInteractions();
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    @Test
    void refresh_validBackofficeToken_reissuesWithCurrentRoles() {
        StaffUser user = executive();
        // El rol cambió después de emitir la sesión: el refresh debe usar el vigente.
        user.changeRoles(Set.of(StaffRole.OPS_SUPERVISOR));
        String raw = "opaqueStaffRefresh";
        AuthToken stored = AuthToken.create(user.getStaffUserId(), null, sha256Hex(raw),
                UUID.randomUUID().toString(), 7, "127.0.0.1", "test-agent", Channel.BACKOFFICE);

        given(tokenRepository.findByRefreshTokenHash(sha256Hex(raw))).willReturn(Optional.of(stored));
        given(staffUserRepository.findById(user.getStaffUserId())).willReturn(Optional.of(user));
        given(sessionTokenIssuer.issueForStaff(eq(user.getStaffUserId()), eq(List.of("OPS_SUPERVISOR")), any(), any()))
                .willReturn(anySession());

        StaffSession session = service.refresh(raw);

        assertThat(session.profile().roles()).containsExactly("OPS_SUPERVISOR");
        assertThat(stored.isRevoked()).isTrue();
    }

    @Test
    void refresh_mobileToken_rejectedByChannelGuard() {
        String raw = "mobileRefresh";
        AuthToken mobileToken = AuthToken.create(UUID.randomUUID(), "device-1", sha256Hex(raw),
                UUID.randomUUID().toString(), 7, "127.0.0.1", null, Channel.MOBILE);
        given(tokenRepository.findByRefreshTokenHash(sha256Hex(raw))).willReturn(Optional.of(mobileToken));

        assertThatThrownBy(() -> service.refresh(raw))
                .isInstanceOf(ChannelMismatchException.class);

        then(sessionTokenIssuer).shouldHaveNoInteractions();
    }

    @Test
    void refresh_suspendedSinceIssue_rejects() {
        StaffUser user = executive();
        user.suspend();
        String raw = "staleRefresh";
        AuthToken stored = AuthToken.create(user.getStaffUserId(), null, sha256Hex(raw),
                UUID.randomUUID().toString(), 7, "127.0.0.1", null, Channel.BACKOFFICE);

        given(tokenRepository.findByRefreshTokenHash(sha256Hex(raw))).willReturn(Optional.of(stored));
        given(staffUserRepository.findById(user.getStaffUserId())).willReturn(Optional.of(user));

        assertThatThrownBy(() -> service.refresh(raw))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void refresh_revokedToken_throws() {
        String raw = "revoked";
        AuthToken stored = AuthToken.create(UUID.randomUUID(), null, sha256Hex(raw),
                UUID.randomUUID().toString(), 7, "127.0.0.1", null, Channel.BACKOFFICE);
        stored.revoke();
        given(tokenRepository.findByRefreshTokenHash(sha256Hex(raw))).willReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.refresh(raw)).isInstanceOf(TokenException.class);
    }

    // ── Logout ────────────────────────────────────────────────────────────

    @Test
    void logout_revokesEverySessionOfTheStaffUser() {
        UUID staffUserId = UUID.randomUUID();

        service.logout(staffUserId);

        then(tokenRepository).should().revokeAllByPartyId(staffUserId);
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private static String sha256Hex(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
