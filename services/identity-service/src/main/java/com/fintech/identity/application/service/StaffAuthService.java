package com.fintech.identity.application.service;

import com.fintech.identity.application.AuthCacheNames;
import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.IssuedSession;
import com.fintech.identity.application.StaffLoginCommand;
import com.fintech.identity.application.StaffProfile;
import com.fintech.identity.application.StaffSession;
import com.fintech.identity.application.port.in.StaffLoginUseCase;
import com.fintech.identity.application.port.out.SessionTokenIssuer;
import com.fintech.identity.application.port.out.StaffUserRepository;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.domain.AccountLockedException;
import com.fintech.identity.domain.AuthToken;
import com.fintech.identity.domain.Channel;
import com.fintech.identity.domain.InvalidCredentialsException;
import com.fintech.identity.domain.StaffUser;
import com.fintech.identity.domain.StaffUserNotFoundException;
import com.fintech.identity.domain.TokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Autenticación del personal de backoffice.
 *
 * <p>Separado de {@link AuthService} a propósito: el sujeto es un {@link StaffUser} y no un party,
 * los roles son variables en vez del fijo {@code CUSTOMER}, y no hay MFA ni tracking de dispositivo
 * en el flujo v1. Lo único que comparten es el emisor de tokens y el store de sesiones.
 */
@Service
@Transactional
public class StaffAuthService implements StaffLoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(StaffAuthService.class);

    private final StaffUserRepository staffUserRepository;
    private final TokenRepository tokenRepository;
    private final SessionTokenIssuer sessionTokenIssuer;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;

    public StaffAuthService(StaffUserRepository staffUserRepository,
                            TokenRepository tokenRepository,
                            SessionTokenIssuer sessionTokenIssuer,
                            PasswordEncoder passwordEncoder,
                            AuthProperties properties) {
        this.staffUserRepository = staffUserRepository;
        this.tokenRepository     = tokenRepository;
        this.sessionTokenIssuer  = sessionTokenIssuer;
        this.passwordEncoder     = passwordEncoder;
        this.properties          = properties;
    }

    @Override
    public StaffSession login(StaffLoginCommand command) {
        String email = normalize(command.email());

        StaffUser user = staffUserRepository.findByEmail(email)
                .orElseThrow(() -> {
                    // Mismo error que una contraseña incorrecta: no revelamos qué correos existen.
                    log.warn("Staff login failed email={} reason=not_found", email);
                    return new InvalidCredentialsException();
                });

        if (user.isLocked()) {
            log.warn("Staff login failed email={} reason=locked until={}", email, user.getLockedUntil());
            throw new AccountLockedException(user.getLockedUntil());
        }

        if (!passwordEncoder.matches(command.password(), user.getPasswordHash())) {
            user.recordFailure(properties.getMaxFailedAttempts(), properties.getLockoutDurationMinutes());
            staffUserRepository.save(user);

            if (user.isLocked()) {
                log.warn("Staff login failed email={} reason=locked_now attempts={}",
                        email, user.getFailedAttempts());
                throw new AccountLockedException(user.getLockedUntil());
            }
            log.warn("Staff login failed email={} reason=bad_credentials attempts={}",
                    email, user.getFailedAttempts());
            throw new InvalidCredentialsException();
        }

        // Una cuenta suspendida o dada de baja acierta la contraseña pero no entra.
        if (!user.canSignIn()) {
            log.warn("Staff login failed email={} reason=status status={}", email, user.getStatus());
            throw new InvalidCredentialsException();
        }

        user.resetFailures();
        user.recordLogin(Instant.now(), command.ipAddress());
        staffUserRepository.save(user);

        log.info("Staff login success email={} staffUserId={} roles={}",
                email, user.getStaffUserId(), user.roleNames());
        return issueSession(user, command.ipAddress(), command.userAgent());
    }

    @Override
    public StaffSession refresh(String rawRefreshToken) {
        AuthToken existing = tokenRepository
                .findByRefreshTokenHash(TokenHashing.sha256Hex(rawRefreshToken))
                .orElseThrow(() -> new TokenException("Refresh token not found"));

        if (existing.isRevoked() || existing.isExpired()) {
            throw new TokenException("Refresh token is invalid or expired");
        }
        existing.requireChannel(Channel.BACKOFFICE);

        existing.revoke();
        tokenRepository.save(existing);

        // El subject de una sesión de backoffice es el staffUserId.
        UUID staffUserId = existing.getPartyId();
        StaffUser user = staffUserRepository.findById(staffUserId)
                .orElseThrow(() -> new StaffUserNotFoundException(staffUserId.toString()));

        // Suspender o dar de baja a alguien surte efecto en el siguiente refresh, sin esperar a que
        // expire el refresh token.
        if (!user.canSignIn()) {
            log.warn("Staff refresh rejected staffUserId={} status={}", staffUserId, user.getStatus());
            throw new TokenException("Staff account is not active");
        }

        return issueSession(user, existing.getIpAddress(), existing.getUserAgent());
    }

    @Override
    @CacheEvict(value = AuthCacheNames.TOKEN_VALIDATION, allEntries = true)
    public void logout(UUID staffUserId) {
        tokenRepository.revokeAllByPartyId(staffUserId);
        log.info("Staff logout staffUserId={}", staffUserId);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** Relee siempre los roles del agregado: un cambio de rol aplica al renovar la sesión. */
    private StaffSession issueSession(StaffUser user, String ipAddress, String userAgent) {
        IssuedSession session = sessionTokenIssuer.issueForStaff(
                user.getStaffUserId(), user.roleNames(), ipAddress, userAgent);
        return new StaffSession(session.pair(), StaffProfile.from(user));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
