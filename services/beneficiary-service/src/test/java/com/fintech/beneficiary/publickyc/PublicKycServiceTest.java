package com.fintech.beneficiary.publickyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.*;
import com.fintech.beneficiary.application.service.GuestSessionGuard;
import com.fintech.beneficiary.application.service.PublicKycService;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * La superficie pública sin HTTP ni base: la liga, el OTP y la sesión de invitado.
 *
 * <p>Las entidades son reales y los repositorios simulados: lo que importa aquí es la regla
 * —topes, congelamiento, vigencia, deslizamiento—, no el SQL, que prueba el IT.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicKycServiceTest {

    @Mock PlacementInviteRepository invites;
    @Mock KycSessionRepository sessions;
    @Mock PlacementRepository placements;
    @Mock KycDocumentRepository documents;
    @Mock ConsentRecordRepository consents;
    @Mock PlacementLifecycleUseCase lifecycle;
    @Mock BeneficiaryOtpSender otpSender;
    @Mock IneReaderPort ineReader;
    @Mock LivenessPort liveness;
    @Mock PartyClient party;

    MutableClock clock;
    BeneficiaryProperties props;
    PublicKycService service;

    Placement placement;
    PlacementInvite invite;
    static final String TOKEN = "token-de-la-liga";

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.now());
        props = new BeneficiaryProperties();
        GuestSessionGuard guard = new GuestSessionGuard(invites, sessions, placements, props, clock);
        service = new PublicKycService(guard, sessions, documents, consents, lifecycle, otpSender,
                ineReader, liveness, party, props, new ObjectMapper(), clock);

        placement = KycFixtures.placement();
        invite = PlacementInvite.mint(placement.getPlacementId(), SecretHash.sha256Hex(TOKEN),
                placement.getInviteExpiresAt(), clock.instant());

        given(invites.findByTokenHash(SecretHash.sha256Hex(TOKEN))).willReturn(Optional.of(invite));
        given(invites.lockByTokenHash(SecretHash.sha256Hex(TOKEN))).willReturn(Optional.of(invite));
        given(invites.findById(invite.getInviteId())).willReturn(Optional.of(invite));
        given(placements.findById(placement.getPlacementId())).willReturn(Optional.of(placement));
        given(party.findByProspect(placement.getDistributorPartyId())).willReturn(Optional.of(
                new PartyClient.PartyResponse(UUID.randomUUID(), placement.getDistributorPartyId(),
                        "Rosa", "Martínez", "López", "ACTIVE", null, null, null)));
        given(sessions.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    /** El código que el adaptador de envío recibió. */
    private String lastSentCode() {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        then(otpSender).should(org.mockito.Mockito.atLeastOnce()).send(anyString(), code.capture(), anyBoolean());
        return code.getValue();
    }

    private KycSession verifiedSession() {
        service.sendOtp(TOKEN);
        var guest = service.verifyOtp(TOKEN, lastSentCode(), "201.141.1.1", "Mozilla");
        ArgumentCaptor<KycSession> saved = ArgumentCaptor.forClass(KycSession.class);
        then(sessions).should(org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        KycSession session = saved.getValue();
        given(sessions.findBySessionTokenHash(SecretHash.sha256Hex(guest.sessionToken())))
                .willReturn(Optional.of(session));
        return session;
    }

    @Nested
    @DisplayName("portada")
    class Invite {

        @Test
        @DisplayName("sin PII antes del OTP: primer nombre, monto y celular enmascarado")
        void noPiiBeforeOtp() {
            var view = service.viewInvite(TOKEN);
            assertThat(view.lenderName()).isEqualTo("Rosa Martínez López");
            assertThat(view.beneficiaryFirstName()).isEqualTo("María");
            assertThat(view.phoneMask()).isEqualTo("55 •• •• 90 37");
            assertThat(view.totalToPay()).isEqualByComparingTo("10416.72");
            assertThat(view.status()).isEqualTo("invited");
        }

        @Test
        @DisplayName("si party no contesta, la distribuidora es «Tu distribuidora» y la portada sigue")
        void lenderFallback() {
            given(party.findByProspect(any())).willReturn(Optional.empty());
            assertThat(service.viewInvite(TOKEN).lenderName()).isEqualTo("Tu distribuidora");
        }

        @Test
        @DisplayName("token inexistente, vencido, revocado o colocación cancelada: la MISMA excepción y mensaje")
        void allInvalidTokensLookTheSame() {
            String unknown = catchMessage(() -> service.viewInvite("no-existe"));

            invite.revoke(clock.instant());
            String revoked = catchMessage(() -> service.viewInvite(TOKEN));

            assertThat(unknown).isEqualTo(revoked);
        }

        @Test
        @DisplayName("una liga vencida no se distingue de una que nunca existió")
        void expiredLooksUnknown() {
            clock.advance(Duration.ofDays(8));
            assertThatThrownBy(() -> service.viewInvite(TOKEN))
                    .isInstanceOf(PublicKycException.InviteNotValid.class);
        }

        @Test
        @DisplayName("una colocación que ya no espera KYC apaga su liga aunque la liga siga viva")
        void cancelledPlacementKillsTheLink() {
            placement.cancel("El distribuidor se arrepintió");
            assertThatThrownBy(() -> service.viewInvite(TOKEN))
                    .isInstanceOf(PublicKycException.InviteNotValid.class);
        }

        private String catchMessage(Runnable r) {
            try {
                r.run();
            } catch (PublicKycException.InviteNotValid e) {
                return e.wireCode() + "|" + e.getMessage();
            }
            throw new AssertionError("se esperaba LIGA_NO_VALIDA");
        }
    }

    @Nested
    @DisplayName("OTP")
    class Otp {

        @Test
        @DisplayName("con código de desarrollo se manda ése y se marca como tal")
        void devCode() {
            props.getPublicKyc().setDevOtpCode("123456");
            var sent = service.sendOtp(TOKEN);
            assertThat(sent.sent()).isTrue();
            assertThat(sent.resendInSeconds()).isEqualTo(45);
            then(otpSender).should().send(KycFixtures.PHONE, "123456", true);
        }

        @Test
        @DisplayName("sin código de desarrollo, seis dígitos aleatorios y en base sólo su hash")
        void randomCodeStoredHashed() {
            service.sendOtp(TOKEN);
            String code = lastSentCode();
            assertThat(code).matches("^\\d{6}$");
            assertThat(invite.getOtpHash()).isEqualTo(SecretHash.otpHash(invite.getInviteId(), code))
                    .isNotEqualTo(code);
            then(otpSender).should().send(KycFixtures.PHONE, code, false);
        }

        @Test
        @DisplayName("el cuarto envío en 15 minutos es DEMASIADOS_INTENTOS y no se manda nada")
        void fourthSendIsRejected() {
            service.sendOtp(TOKEN);
            service.sendOtp(TOKEN);
            service.sendOtp(TOKEN);
            assertThatThrownBy(() -> service.sendOtp(TOKEN))
                    .isInstanceOfSatisfying(PublicKycException.TooManyAttempts.class,
                            e -> assertThat(e.wireCode()).isEqualTo("DEMASIADOS_INTENTOS"));
            then(otpSender).should(org.mockito.Mockito.times(3)).send(anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("código correcto: sesión de 15 min, sólo su hash en base, y la colocación pasa a KYC_IN_PROGRESS")
        void verifyOpensSession() {
            service.sendOtp(TOKEN);
            var guest = service.verifyOtp(TOKEN, lastSentCode(), "201.141.1.1", "Mozilla");

            assertThat(guest.sessionToken()).hasSize(43);
            assertThat(guest.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
            ArgumentCaptor<KycSession> saved = ArgumentCaptor.forClass(KycSession.class);
            then(sessions).should().save(saved.capture());
            assertThat(saved.getValue().getSessionTokenHash())
                    .isEqualTo(SecretHash.sha256Hex(guest.sessionToken()));
            then(lifecycle).should().startKyc(placement.getPlacementId());
        }

        @Test
        @DisplayName("reanudar dentro de los 7 días no vuelve a arrancar el KYC")
        void resumeDoesNotRestartKyc() {
            placement.startKyc();
            service.sendOtp(TOKEN);
            service.verifyOtp(TOKEN, lastSentCode(), null, null);
            then(lifecycle).should(never()).startKyc(any());
        }

        @Test
        @DisplayName("código equivocado: CODIGO_INCORRECTO con intentos restantes, sin sesión")
        void wrongCode() {
            service.sendOtp(TOKEN);
            String good = lastSentCode();
            String bad = good.equals("000000") ? "111111" : "000000";
            assertThatThrownBy(() -> service.verifyOtp(TOKEN, bad, null, null))
                    .isInstanceOfSatisfying(PublicKycException.WrongCode.class,
                            e -> assertThat(e.attemptsLeft()).isEqualTo(4));
            then(sessions).should(never()).save(any());
        }

        @Test
        @DisplayName("cinco equivocados congelan la liga una hora; pasada la hora se puede volver a empezar")
        void freezeAndThaw() {
            service.sendOtp(TOKEN);
            String good = lastSentCode();
            String bad = good.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> service.verifyOtp(TOKEN, bad, null, null))
                        .isInstanceOf(PublicKycException.WrongCode.class);
            }
            assertThatThrownBy(() -> service.verifyOtp(TOKEN, bad, null, null))
                    .isInstanceOf(PublicKycException.TooManyAttempts.class);
            assertThatThrownBy(() -> service.sendOtp(TOKEN))
                    .isInstanceOf(PublicKycException.TooManyAttempts.class);

            clock.advance(Duration.ofMinutes(61));
            service.sendOtp(TOKEN);
            assertThat(service.verifyOtp(TOKEN, lastSentCode(), null, null).sessionToken()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("sesión de invitado")
    class Session {

        @Test
        @DisplayName("sin header o con un token desconocido: SESION_EXPIRADA")
        void missingSession() {
            assertThatThrownBy(() -> service.state(null)).isInstanceOf(PublicKycException.SessionExpired.class);
            assertThatThrownBy(() -> service.state("inventado")).isInstanceOf(PublicKycException.SessionExpired.class);
        }

        @Test
        @DisplayName("cada paso desliza 15 minutos; 15 minutos sin moverse y caduca")
        void slidingExpiry() {
            service.sendOtp(TOKEN);
            var guest = service.verifyOtp(TOKEN, lastSentCode(), null, null);
            ArgumentCaptor<KycSession> saved = ArgumentCaptor.forClass(KycSession.class);
            then(sessions).should().save(saved.capture());
            KycSession session = saved.getValue();
            given(sessions.findBySessionTokenHash(SecretHash.sha256Hex(guest.sessionToken())))
                    .willReturn(Optional.of(session));

            clock.advance(Duration.ofMinutes(10));
            assertThat(service.state(guest.sessionToken()).step()).isEqualTo("INE");
            assertThat(session.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

            clock.advance(Duration.ofMinutes(14));
            assertThat(service.state(guest.sessionToken()).step()).as("aún dentro de la ventana").isEqualTo("INE");

            clock.advance(Duration.ofMinutes(15));
            assertThatThrownBy(() -> service.state(guest.sessionToken()))
                    .isInstanceOf(PublicKycException.SessionExpired.class);
        }

        @Test
        @DisplayName("la sesión nunca vive más que su liga")
        void sessionCappedByInvite() {
            clock.advance(Duration.between(clock.instant(), invite.getExpiresAt()).minusMinutes(5));
            service.sendOtp(TOKEN);
            var guest = service.verifyOtp(TOKEN, lastSentCode(), null, null);
            assertThat(guest.expiresAt()).isEqualTo(invite.getExpiresAt());
        }

        @Test
        @DisplayName("si el distribuidor cancela a media captura, la sesión de ella muere")
        void revokedMidway() {
            KycSession session = verifiedSession();
            String raw = "x";
            given(sessions.findBySessionTokenHash(SecretHash.sha256Hex(raw))).willReturn(Optional.of(session));
            placement.cancel("Revocada");
            assertThatThrownBy(() -> service.state(raw)).isInstanceOf(PublicKycException.SessionExpired.class);
        }

        @Test
        @DisplayName("datos inválidos: DATOS_INVALIDOS con el campo, y no se guarda nada")
        void invalidDataIsRejected() {
            KycSession session = verifiedSession();
            given(sessions.findBySessionTokenHash(SecretHash.sha256Hex("s"))).willReturn(Optional.of(session));
            var d = KycFixtures.data();
            var bad = new com.fintech.beneficiary.application.KycData(d.firstName(), d.lastName1(),
                    d.lastName2(), "NO-ES-CURP", d.rfc(), d.dateOfBirth(), d.occupation(), d.monthlyIncome(),
                    d.street(), d.exteriorNumber(), d.neighborhood(), d.municipality(), d.city(), d.state(),
                    d.postalCode(), d.clabe(), null);
            assertThatThrownBy(() -> service.saveData("s", bad))
                    .isInstanceOfSatisfying(PublicKycException.InvalidData.class,
                            e -> assertThat(e.fields()).containsKey("curp"));
            assertThat(session.getCapturedData()).isNull();
        }

        @Test
        @DisplayName("un PDF no es una selfie")
        void selfieMustBeAnImage() {
            KycSession session = verifiedSession();
            given(sessions.findBySessionTokenHash(SecretHash.sha256Hex("s"))).willReturn(Optional.of(session));
            assertThatThrownBy(() -> service.uploadSelfie("s",
                    new PublicKycService.Upload(new byte[]{1, 2, 3}, "application/pdf", "selfie.pdf")))
                    .isInstanceOfSatisfying(PublicKycException.InvalidData.class,
                            e -> assertThat(e.fields()).containsKey("selfie"));
            then(documents).should(never()).save(any());
        }

        @Test
        @DisplayName("consentimientos: los tres o ninguno")
        void consentsAllOrNothing() {
            KycSession session = verifiedSession();
            given(sessions.findBySessionTokenHash(SecretHash.sha256Hex("s"))).willReturn(Optional.of(session));
            assertThatThrownBy(() -> service.acceptConsents("s",
                    new PublicKycService.ConsentsCommand(true, false, true, "v1"), "1.1.1.1", "ua"))
                    .isInstanceOfSatisfying(PublicKycException.InvalidData.class,
                            e -> assertThat(e.fields()).containsOnlyKeys("bureauAuthorization"));
            then(consents).should(never()).save(any());

            service.acceptConsents("s", new PublicKycService.ConsentsCommand(true, true, true, "v1"), "1.1.1.1", "ua");
            then(consents).should(org.mockito.Mockito.times(3)).save(any());
        }
    }
}
