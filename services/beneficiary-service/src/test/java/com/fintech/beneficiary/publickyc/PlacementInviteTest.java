package com.fintech.beneficiary.publickyc;

import com.fintech.beneficiary.domain.PlacementInvite;
import com.fintech.beneficiary.domain.PlacementValidationException;
import com.fintech.beneficiary.domain.PublicKycException;
import com.fintech.beneficiary.domain.SecretHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La liga como objeto de dominio: su secreto, su vigencia y sus topes.
 *
 * <p>Todo con instantes explícitos: lo que se prueba aquí es tiempo, y un test que dependiera del
 * reloj de la máquina fallaría a las 23:59.
 */
class PlacementInviteTest {

    private static final Instant T0 = Instant.parse("2026-10-29T15:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration FREEZE = Duration.ofMinutes(60);

    private static PlacementInvite invite() {
        return PlacementInvite.mint(UUID.randomUUID(), SecretHash.sha256Hex(SecretHash.randomToken()),
                T0.plus(Duration.ofDays(7)), T0);
    }

    @Nested
    @DisplayName("el secreto")
    class Secret {

        @Test
        @DisplayName("el token es de 32 bytes URL-safe y en base sólo queda su SHA-256 de 64 hex")
        void tokenAndHash() {
            String token = SecretHash.randomToken();
            assertThat(token).hasSize(43).matches("^[A-Za-z0-9_-]+$");
            String hash = SecretHash.sha256Hex(token);
            assertThat(hash).hasSize(64).matches("^[0-9a-f]+$").isNotEqualTo(token);
            assertThat(SecretHash.sha256Hex(token)).as("determinista").isEqualTo(hash);
        }

        @Test
        @DisplayName("cada token es distinto — mil seguidos sin una sola colisión")
        void tokensAreUnique() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 1000; i++) seen.add(SecretHash.randomToken());
            assertThat(seen).hasSize(1000);
        }

        @Test
        @DisplayName("el OTP es de seis dígitos y su hash va salado con la liga")
        void otpIsSaltedByInvite() {
            String code = SecretHash.randomOtpCode();
            assertThat(code).matches("^\\d{6}$");
            assertThat(SecretHash.otpHash(UUID.randomUUID(), "123456"))
                    .isNotEqualTo(SecretHash.otpHash(UUID.randomUUID(), "123456"));
        }

        @Test
        @DisplayName("comparar hashes tolera nulos sin explotar")
        void matchesIsNullSafe() {
            assertThat(SecretHash.matches(null, "a")).isFalse();
            assertThat(SecretHash.matches("a", null)).isFalse();
            assertThat(SecretHash.matches("abc", "abc")).isTrue();
        }

        @Test
        @DisplayName("no se acuña una liga sin un hash de 64 caracteres")
        void mintRequiresAHash() {
            assertThatThrownBy(() -> PlacementInvite.mint(UUID.randomUUID(), "corto", T0, T0))
                    .isInstanceOf(PlacementValidationException.class);
        }
    }

    @Nested
    @DisplayName("vigencia")
    class Validity {

        @Test
        @DisplayName("viva hasta su vencimiento, muerta en el instante exacto")
        void expiresAtTheInstant() {
            PlacementInvite i = invite();
            assertThat(i.isRedeemable(T0)).isTrue();
            assertThat(i.isRedeemable(T0.plus(Duration.ofDays(7)).minusSeconds(1))).isTrue();
            assertThat(i.isRedeemable(T0.plus(Duration.ofDays(7)))).isFalse();
        }

        @Test
        @DisplayName("revocada o quemada, no abre nada aunque le quede vida")
        void revokedOrRedeemedIsDead() {
            PlacementInvite revoked = invite();
            revoked.revoke(T0);
            assertThat(revoked.isRedeemable(T0)).isFalse();

            PlacementInvite redeemed = invite();
            redeemed.redeem(T0);
            assertThat(redeemed.isRedeemable(T0)).isFalse();
        }
    }

    @Nested
    @DisplayName("envío de códigos")
    class Sends {

        @Test
        @DisplayName("tres envíos por ventana de 15 minutos; el cuarto es 429")
        void threePerWindow() {
            PlacementInvite i = invite();
            for (int n = 0; n < 3; n++) {
                i.registerOtpSend(SecretHash.otpHash(i.getInviteId(), "11111" + n), T0.plusSeconds(300), T0, 3, WINDOW);
            }
            assertThatThrownBy(() -> i.registerOtpSend("x".repeat(64), T0.plusSeconds(300), T0, 3, WINDOW))
                    .isInstanceOf(PublicKycException.TooManyAttempts.class)
                    .satisfies(e -> assertThat(((PublicKycException.TooManyAttempts) e).retryAfterSeconds())
                            .isEqualTo(WINDOW.toSeconds()));
        }

        @Test
        @DisplayName("pasada la ventana, el contador vuelve a cero")
        void windowResets() {
            PlacementInvite i = invite();
            for (int n = 0; n < 3; n++) i.registerOtpSend("a".repeat(64), T0.plusSeconds(300), T0, 3, WINDOW);

            Instant later = T0.plus(WINDOW);
            i.registerOtpSend("b".repeat(64), later.plusSeconds(300), later, 3, WINDOW);
            assertThat(i.getOtpSendCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("un código nuevo invalida el anterior")
        void newCodeReplacesTheOld() {
            PlacementInvite i = invite();
            String first = SecretHash.otpHash(i.getInviteId(), "111111");
            String second = SecretHash.otpHash(i.getInviteId(), "222222");
            i.registerOtpSend(first, T0.plusSeconds(300), T0, 3, WINDOW);
            i.registerOtpSend(second, T0.plusSeconds(300), T0, 3, WINDOW);

            assertThatThrownBy(() -> i.verifyOtp(first, T0, 5, FREEZE))
                    .isInstanceOf(PublicKycException.WrongCode.class);
            assertThat(i.verifyOtp(second, T0, 5, FREEZE)).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("verificación")
    class Verify {

        @Test
        @DisplayName("el código correcto se consume: no abre una segunda sesión")
        void correctCodeIsConsumed() {
            PlacementInvite i = invite();
            String h = SecretHash.otpHash(i.getInviteId(), "123456");
            i.registerOtpSend(h, T0.plusSeconds(300), T0, 3, WINDOW);

            assertThat(i.verifyOtp(h, T0, 5, FREEZE)).isEqualTo(h);
            assertThatThrownBy(() -> i.verifyOtp(h, T0, 5, FREEZE))
                    .isInstanceOf(PublicKycException.WrongCode.class);
            assertThat(i.getFailedAttempts()).as("sin código vigente no se cuenta intento").isZero();
        }

        @Test
        @DisplayName("un código vencido no entra, aunque sea el correcto")
        void expiredCodeFails() {
            PlacementInvite i = invite();
            String h = SecretHash.otpHash(i.getInviteId(), "123456");
            i.registerOtpSend(h, T0.plusSeconds(300), T0, 3, WINDOW);

            assertThatThrownBy(() -> i.verifyOtp(h, T0.plusSeconds(300), 5, FREEZE))
                    .isInstanceOf(PublicKycException.WrongCode.class);
        }

        @Test
        @DisplayName("cada código equivocado descuenta un intento y lo dice")
        void wrongCodeCountsDown() {
            PlacementInvite i = invite();
            i.registerOtpSend(SecretHash.otpHash(i.getInviteId(), "123456"), T0.plusSeconds(300), T0, 3, WINDOW);

            assertThatThrownBy(() -> i.verifyOtp(SecretHash.otpHash(i.getInviteId(), "000000"), T0, 5, FREEZE))
                    .isInstanceOfSatisfying(PublicKycException.WrongCode.class,
                            e -> assertThat(e.attemptsLeft()).isEqualTo(4));
        }

        @Test
        @DisplayName("al quinto equivocado la liga se congela una hora — ni el correcto entra")
        void fifthFailureFreezes() {
            PlacementInvite i = invite();
            String good = SecretHash.otpHash(i.getInviteId(), "123456");
            String bad = SecretHash.otpHash(i.getInviteId(), "000000");
            i.registerOtpSend(good, T0.plusSeconds(300), T0, 3, WINDOW);

            for (int n = 0; n < 4; n++) {
                assertThatThrownBy(() -> i.verifyOtp(bad, T0, 5, FREEZE))
                        .isInstanceOf(PublicKycException.WrongCode.class);
            }
            assertThatThrownBy(() -> i.verifyOtp(bad, T0, 5, FREEZE))
                    .isInstanceOf(PublicKycException.TooManyAttempts.class);

            assertThat(i.isFrozen(T0.plus(FREEZE).minusSeconds(1))).isTrue();
            assertThatThrownBy(() -> i.verifyOtp(good, T0.plusSeconds(10), 5, FREEZE))
                    .isInstanceOf(PublicKycException.TooManyAttempts.class);
            assertThatThrownBy(() -> i.registerOtpSend(good, T0.plusSeconds(300), T0.plusSeconds(10), 3, WINDOW))
                    .as("congelada tampoco manda códigos")
                    .isInstanceOf(PublicKycException.TooManyAttempts.class);

            // Pasada la hora, vuelve a poder pedir código — el congelado también tiró el vigente.
            Instant thawed = T0.plus(FREEZE);
            assertThat(i.isFrozen(thawed)).isFalse();
            assertThat(i.getOtpHash()).isNull();
        }
    }
}
