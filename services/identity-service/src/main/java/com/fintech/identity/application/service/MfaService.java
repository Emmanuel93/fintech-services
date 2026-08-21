package com.fintech.identity.application.service;

import com.fintech.identity.application.AuthProperties;
import com.fintech.identity.application.DeviceTrackingResult;
import com.fintech.identity.application.IssuedSession;
import com.fintech.identity.application.MfaEnrollmentResult;
import com.fintech.identity.application.MfaVerifyCommand;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.event.LoginAttemptEvent;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.application.port.out.*;
import com.fintech.identity.application.port.out.MfaPendingRepository.MfaPendingData;
import com.fintech.identity.domain.*;
import com.fintech.identity.domain.event.GeoLocation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class MfaService implements EnrollMfaUseCase, ConfirmMfaEnrollmentUseCase,
                                    VerifyMfaUseCase, DisableMfaUseCase {

    private static final String ISSUER = "fintech-services";

    private final MfaRepository mfaRepository;
    private final MfaPendingRepository mfaPendingRepository;
    private final TotpPort totpPort;
    private final SessionTokenIssuer sessionTokenIssuer;
    private final AuthProperties properties;
    private final GeoLocationPort geoLocationPort;
    private final LoginEventPublisher loginEventPublisher;
    private final DeviceTracker deviceTracker;

    public MfaService(MfaRepository mfaRepository,
                      MfaPendingRepository mfaPendingRepository,
                      TotpPort totpPort,
                      SessionTokenIssuer sessionTokenIssuer,
                      AuthProperties properties,
                      GeoLocationPort geoLocationPort,
                      LoginEventPublisher loginEventPublisher,
                      DeviceTracker deviceTracker) {
        this.mfaRepository = mfaRepository;
        this.mfaPendingRepository = mfaPendingRepository;
        this.totpPort = totpPort;
        this.sessionTokenIssuer = sessionTokenIssuer;
        this.properties = properties;
        this.geoLocationPort = geoLocationPort;
        this.loginEventPublisher = loginEventPublisher;
        this.deviceTracker = deviceTracker;
    }

    @Override
    public MfaEnrollmentResult enroll(UUID partyId) {
        mfaRepository.findByPartyId(partyId).ifPresent(existing -> {
            if (existing.isEnabled()) throw new MfaAlreadyEnrolledException();
        });

        String secret = totpPort.generateSecret();
        MfaConfig config = mfaRepository.findByPartyId(partyId)
                .orElseGet(() -> MfaConfig.create(partyId, secret));

        mfaRepository.save(config);

        String qrUri = totpPort.buildQrUri(config.getTotpSecret(), ISSUER, partyId.toString());
        return new MfaEnrollmentResult(config.getTotpSecret(), qrUri);
    }

    @Override
    public void confirmEnrollment(UUID partyId, String totpCode) {
        MfaConfig config = mfaRepository.findByPartyId(partyId)
                .orElseThrow(MfaNotEnrolledException::new);

        if (config.isEnabled()) throw new MfaAlreadyEnrolledException();

        if (!totpPort.verifyCode(config.getTotpSecret(), totpCode, properties.getMfaCodeWindowSize())) {
            throw new InvalidMfaCodeException();
        }

        config.activate();
        mfaRepository.save(config);
    }

    @Override
    public TokenPair verify(MfaVerifyCommand command) {
        MfaPendingData pending = mfaPendingRepository.consume(command.mfaToken())
                .orElseThrow(() -> new TokenException("MFA token not found or expired"));

        UUID partyId = pending.partyId();
        String username = pending.username();
        GeoLocation geo = geoLocationPort.resolve(command.ipAddress());

        // En el paso 2 del login (MFA verify) no tenemos deviceId en el command
        // — el device fue registrado en el paso 1. Si el cliente lo reenvía
        // en headers futuros podría pasarse; por ahora usamos none().
        DeviceTrackingResult device = DeviceTrackingResult.none();

        MfaConfig config = mfaRepository.findByPartyId(partyId)
                .filter(MfaConfig::isEnabled)
                .orElseThrow(MfaNotEnrolledException::new);

        if (!totpPort.verifyCode(config.getTotpSecret(), command.totpCode(),
                properties.getMfaCodeWindowSize())) {
            loginEventPublisher.publish(
                    LoginAttemptEvent.failedMfa(command, partyId, username, geo, device));
            throw new InvalidMfaCodeException();
        }

        IssuedSession session = sessionTokenIssuer.issue(
                partyId, null, command.ipAddress(), command.userAgent());
        loginEventPublisher.publish(
                LoginAttemptEvent.successWithMfa(command, partyId, username, geo,
                        session.jti(), session.expiresAt(), device));
        return session.pair();
    }

    @Override
    public void disable(UUID partyId, String totpCode) {
        MfaConfig config = mfaRepository.findByPartyId(partyId)
                .filter(MfaConfig::isEnabled)
                .orElseThrow(MfaNotEnrolledException::new);

        if (!totpPort.verifyCode(config.getTotpSecret(), totpCode, properties.getMfaCodeWindowSize())) {
            throw new InvalidMfaCodeException();
        }

        config.disable();
        mfaRepository.save(config);
    }
}
