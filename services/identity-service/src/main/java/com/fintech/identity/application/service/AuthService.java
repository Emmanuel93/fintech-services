package com.fintech.identity.application.service;

import com.fintech.identity.application.AuthCacheNames;
import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.DeviceTrackingResult;
import com.fintech.identity.application.IssuedSession;
import com.fintech.identity.application.LoginCommand;
import com.fintech.identity.application.LoginResult;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.TokenValidationResult;
import com.fintech.identity.application.event.LoginAttemptEvent;
import com.fintech.identity.application.port.in.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fintech.identity.application.port.out.CredentialRepository;
import com.fintech.identity.application.port.out.GeoLocationPort;
import com.fintech.identity.application.port.out.LoginEventPublisher;
import com.fintech.identity.application.port.out.MfaPendingRepository;
import com.fintech.identity.application.port.out.MfaRepository;
import com.fintech.identity.application.port.out.SessionTokenIssuer;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.domain.AccountLockedException;
import com.fintech.identity.domain.AuthToken;
import com.fintech.identity.domain.Channel;
import com.fintech.identity.domain.ChannelMismatchException;
import com.fintech.identity.domain.CredentialNotFoundException;
import com.fintech.identity.domain.CredentialType;
import com.fintech.identity.domain.IdentityCredential;
import com.fintech.identity.domain.InvalidCredentialsException;
import com.fintech.identity.domain.TokenException;
import com.fintech.identity.domain.event.GeoLocation;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class AuthService implements LoginUseCase, RefreshTokenUseCase, LogoutUseCase,
                                     ValidateTokenUseCase, CreateCredentialUseCase,
                                     ProvisionProspectCredentialUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final CredentialRepository credentialRepository;
    private final TokenRepository tokenRepository;
    private final TokenPort tokenPort;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties properties;
    private final MfaRepository mfaRepository;
    private final MfaPendingRepository mfaPendingRepository;
    private final GeoLocationPort geoLocationPort;
    private final LoginEventPublisher loginEventPublisher;
    private final SessionTokenIssuer sessionTokenIssuer;
    private final DeviceTracker deviceTracker;

    public AuthService(CredentialRepository credentialRepository,
                       TokenRepository tokenRepository,
                       TokenPort tokenPort,
                       PasswordEncoder passwordEncoder,
                       AuthProperties properties,
                       MfaRepository mfaRepository,
                       MfaPendingRepository mfaPendingRepository,
                       GeoLocationPort geoLocationPort,
                       LoginEventPublisher loginEventPublisher,
                       SessionTokenIssuer sessionTokenIssuer,
                       DeviceTracker deviceTracker) {
        this.credentialRepository = credentialRepository;
        this.tokenRepository = tokenRepository;
        this.tokenPort = tokenPort;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.mfaRepository = mfaRepository;
        this.mfaPendingRepository = mfaPendingRepository;
        this.geoLocationPort = geoLocationPort;
        this.loginEventPublisher = loginEventPublisher;
        this.sessionTokenIssuer = sessionTokenIssuer;
        this.deviceTracker = deviceTracker;
    }

    // ── IA-01 / IA-02 ─────────────────────────────────────────────────────

    @Override
    public LoginResult login(LoginCommand command) {
        GeoLocation geo = geoLocationPort.resolve(command.ipAddress());

        IdentityCredential credential;
        try {
            credential = credentialRepository
                    .findByUsername(command.username())
                    .orElseThrow(() -> new CredentialNotFoundException(command.username()));
        } catch (CredentialNotFoundException e) {
            log.warn("Login failed username={} reason=credential_not_found", command.username());
            loginEventPublisher.publish(LoginAttemptEvent.credentialNotFound(command, geo));
            throw e;
        }

        // Tracking de dispositivo: disponible desde aquí porque ya tenemos partyId
        DeviceTrackingResult device = trackDevice(
                credential.getPartyId(), command.deviceId(),
                command.userAgent(), command.ipAddress());

        if (credential.isLocked()) {
            log.warn("Login failed username={} reason=account_locked until={}", command.username(), credential.getLockedUntil());
            loginEventPublisher.publish(
                    LoginAttemptEvent.accountAlreadyLocked(command, credential, geo, device));
            throw new AccountLockedException(credential.getLockedUntil());
        }

        if (!passwordEncoder.matches(command.password(), credential.getPasswordHash())) {
            credential.recordFailure(
                    properties.getMaxFailedAttempts(),
                    properties.getLockoutDurationMinutes());
            credentialRepository.save(credential);

            if (credential.isLocked()) {
                log.warn("Login failed username={} reason=account_locked_now attempts={}",
                        command.username(), credential.getFailedAttempts());
                loginEventPublisher.publish(
                        LoginAttemptEvent.accountLocked(command, credential, geo, device));
                throw new AccountLockedException(credential.getLockedUntil());
            }
            log.warn("Login failed username={} reason=bad_credentials attempts={}",
                    command.username(), credential.getFailedAttempts());
            loginEventPublisher.publish(
                    LoginAttemptEvent.failedCredentials(command, credential, geo, device));
            throw new InvalidCredentialsException();
        }

        credential.resetFailures();
        credential.recordLogin(Instant.now(), command.ipAddress());
        credentialRepository.save(credential);

        UUID partyId = credential.getPartyId();
        boolean mfaActive = mfaRepository.findByPartyId(partyId)
                .map(mfa -> mfa.isEnabled())
                .orElse(false);

        if (mfaActive) {
            String mfaToken = mfaPendingRepository.store(partyId, command.deviceId(), command.username());
            loginEventPublisher.publish(
                    LoginAttemptEvent.mfaRequired(command, credential, geo, device));
            return new LoginResult.MfaRequired(mfaToken);
        }

        IssuedSession session = sessionTokenIssuer.issue(
                partyId, command.deviceId(), command.ipAddress(), command.userAgent());
        loginEventPublisher.publish(
                LoginAttemptEvent.success(command, credential, geo,
                        session.jti(), session.expiresAt(), device));
        log.info("Login success username={} partyId={}", command.username(), partyId);
        return new LoginResult.TokensIssued(session.pair());
    }

    @Override
    public TokenPair refresh(String rawRefreshToken) {
        String hash = TokenHashing.sha256Hex(rawRefreshToken);
        AuthToken existing = tokenRepository
                .findByRefreshTokenHash(hash)
                .orElseThrow(() -> new TokenException("Refresh token not found"));

        if (existing.isRevoked() || existing.isExpired()) {
            throw new TokenException("Refresh token is invalid or expired");
        }

        // Una sesión de backoffice se renueva en /auth/staff/refresh, que relee los roles del
        // empleado. Refrescarla aquí la degradaría a una sesión de cliente. Las sesiones SERVICE
        // (client-credentials) siguen pasando por aquí como hasta ahora.
        if (existing.getChannel() == Channel.BACKOFFICE) {
            throw new ChannelMismatchException(Channel.MOBILE, Channel.BACKOFFICE);
        }

        existing.revoke();
        tokenRepository.save(existing);

        return sessionTokenIssuer.issue(
                existing.getPartyId(), existing.getDeviceId(),
                existing.getIpAddress(), existing.getUserAgent()).pair();
    }

    @Override
    @CacheEvict(value = AuthCacheNames.TOKEN_VALIDATION, allEntries = true)
    public void logout(UUID partyId) {
        tokenRepository.revokeAllByPartyId(partyId);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = AuthCacheNames.TOKEN_VALIDATION, key = "#bearerToken")
    public TokenValidationResult validate(String bearerToken) {
        return tokenPort.validateToken(bearerToken)
                .map(claims -> {
                    if (!tokenRepository.isTokenActive(claims.jti())) {
                        throw new TokenException("Token has been revoked");
                    }
                    return new TokenValidationResult(
                            UUID.fromString(claims.subject()),
                            claims.roles(),
                            claims.deviceId(),
                            claims.channel());
                })
                .orElseThrow(() -> new TokenException("Token is invalid or expired"));
    }

    @Override
    public void createCredential(UUID partyId, String username, CredentialType type, String rawPassword) {
        if (credentialRepository.findByPartyIdAndCredentialType(partyId, type).isPresent()) {
            throw new IllegalStateException("Credential of type " + type + " already exists for party " + partyId);
        }
        String hash = passwordEncoder.encode(rawPassword);
        credentialRepository.save(IdentityCredential.create(partyId, username, type, hash));
    }

    @Override
    public void provisionFromProspect(UUID prospectId, String username, String rawPassword) {
        if (credentialRepository.findByPartyIdAndCredentialType(prospectId, CredentialType.PASSWORD).isPresent()) {
            log.warn("Credential already exists for prospect={}, skipping provisioning", prospectId);
            return;
        }
        String hash = passwordEncoder.encode(rawPassword);
        credentialRepository.save(IdentityCredential.create(prospectId, username, CredentialType.PASSWORD, hash));
        log.info("Credential provisioned for prospect={} username={}", prospectId, username);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private DeviceTrackingResult trackDevice(UUID partyId, String deviceId,
                                              String userAgent, String ipAddress) {
        if (deviceId == null || deviceId.isBlank()) {
            return DeviceTrackingResult.none();
        }
        return deviceTracker.track(partyId, deviceId, userAgent, ipAddress);
    }

}
