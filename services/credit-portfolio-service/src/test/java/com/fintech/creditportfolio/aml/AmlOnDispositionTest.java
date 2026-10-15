package com.fintech.creditportfolio.aml;

import com.fintech.creditportfolio.domain.PartyVerification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AML en cada disposición — y por qué el KYC <b>no</b> se repite aquí.
 *
 * <h2>La regla que estas pruebas fijan</h2>
 *
 * <pre>
 * ORIGINAR:  identidad VERIFIED  ∧  listas CLEARED
 *
 * DISPONER:  autenticación del RESPONSABLE (NIP + OTP)     ← quien autoriza
 *          ∧ listas CLEARED del responsable
 *          ∧ listas CLEARED de QUIEN RECIBE                ← a quien se le paga
 *          ∧ identidad del responsable no revocada
 * </pre>
 *
 * <p>Esta clase prueba el <b>veredicto de una persona</b>: qué deja pasar y qué no. A <b>quién</b>
 * se le aplica —y que son dos papeles, no uno— lo prueba {@link AmlChecksBothRolesTest}.</p>
 *
 * <p>La asimetría es deliberada. El KYC estableció quién es la persona <b>una vez</b>; repetirlo en
 * cada disposición sería pedir una selfie para sacar dinero de la propia línea cada mes — no añade
 * seguridad y garantiza que el producto se abandone. Lo que demuestra que es él en cada disposición
 * es la autenticación, no la biometría.
 *
 * <p>El AML sí se repite, y es lo más importante de todo esto: <b>no depende de la persona sino de
 * la lista, y la lista cambia</b>. Originar ocurre una vez; disponer, decenas de veces durante años.
 */
class AmlOnDispositionTest {

    private static PartyVerification verification(String identity, String screening) {
        boolean pasa = "VERIFIED".equals(identity) && "CLEARED".equals(screening);
        return PartyVerification.of(UUID.randomUUID(), identity, screening, pasa,
                pasa ? null : "identidad " + identity + " / listas " + screening);
    }

    // ── Lo que deja pasar ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("con las listas despejadas, dispone")
    void clearedListsAllowDisposition() {
        assertThat(verification("VERIFIED", "CLEARED").allowsDisposition()).isTrue();
    }

    @Test
    @DisplayName("una identidad EN REVISIÓN no impide disponer: el KYC ya se hizo al originar")
    void identityInReviewDoesNotBlockADisposition() {
        // Es la diferencia con la compuerta de originación, y es el punto de todo el diseño.
        // Alguien cuya línea ya está abierta no tiene que volver a enrolarse para usarla: quien
        // demuestra que es él en ese momento es el NIP y el OTP.
        var v = verification("IN_REVIEW", "CLEARED");

        assertThat(v.allowsDisposition())
                .as("el KYC es el enrolamiento; la autenticación es el uso")
                .isTrue();
        assertThat(v.allowsToProceed())
                .as("pero NO alcanza para originar crédito nuevo")
                .isFalse();
    }

    // ── Lo que bloquea ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una coincidencia de listas detiene la disposición aunque la línea ya exista")
    void aListMatchStopsTheMoney() {
        // Éste es el caso que justifica repetir el AML: la línea se abrió con la persona limpia y
        // hoy está publicada. Sin esta comprobación, el dinero sale igual.
        assertThat(verification("VERIFIED", "POTENTIAL_MATCH").allowsDisposition()).isFalse();
        assertThat(verification("VERIFIED", "CONFIRMED_MATCH").allowsDisposition()).isFalse();
    }

    @Test
    @DisplayName("un veredicto de listas PENDIENTE también detiene: no saber no es estar limpio")
    void pendingScreeningStopsTheMoney() {
        assertThat(verification("VERIFIED", "PENDING").allowsDisposition()).isFalse();
    }

    @Test
    @DisplayName("una identidad REVOCADA sí detiene — es distinto de «no verificada»")
    void arevokedIdentityStopsTheMoney() {
        // `REJECTED` no es «todavía no se comprobó»: es que se comprobó y se cayó —suplantación,
        // fraude—. Eso sí tiene que frenar el dinero de una línea que ya existía.
        assertThat(verification("REJECTED", "CLEARED").allowsDisposition()).isFalse();
        assertThat(verification("REJECTED", "CLEARED").dispositionBlockReason())
                .isEqualTo("identidad revocada");
    }

    @Test
    @DisplayName("el motivo distingue identidad revocada de coincidencia en listas")
    void theReasonDistinguishesTheTwoCases() {
        // No es cosmético: a una coincidencia de listas no se le pide nada a la persona, y a una
        // identidad revocada tampoco es lo mismo. Un rechazo mudo obliga a investigar cada caso.
        assertThat(verification("VERIFIED", "POTENTIAL_MATCH").dispositionBlockReason())
                .isEqualTo("listas POTENTIAL_MATCH");
        assertThat(verification("REJECTED", "CLEARED").dispositionBlockReason())
                .isEqualTo("identidad revocada");
    }

    @Test
    @DisplayName("las dos compuertas son distintas, y esa diferencia es el diseño")
    void thetwoGatesDiffer() {
        var enRevision = verification("IN_REVIEW", "CLEARED");
        var enListas   = verification("VERIFIED", "POTENTIAL_MATCH");

        // Originar exige las dos cosas.
        assertThat(enRevision.allowsToProceed()).isFalse();
        assertThat(enListas.allowsToProceed()).isFalse();

        // Disponer exige listas limpias e identidad no revocada; el KYC ya está hecho.
        assertThat(enRevision.allowsDisposition()).isTrue();
        assertThat(enListas.allowsDisposition()).isFalse();
    }
}
