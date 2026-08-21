package com.fintech.stp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regresión del bug más caro del legado.
 *
 * <p>El legado decidía si STP había rechazado la orden con
 * {@code Integer.toString(id).length() <= 3}. El código {@code -200} (RECHAZO_POR_PLD) tiene cuatro
 * caracteres, así que no se detectaba: la orden se marcaba exitosa y se reportaba como dispersada.
 */
class BanxicoResponseCodeTest {

    @Test
    @DisplayName("B1 · -200 RECHAZO_POR_PLD se detecta y es terminal")
    void pldRejectionIsDetected() {
        assertThat("-200".length()).isEqualTo(4);   // por qué el predicado del legado fallaba

        BanxicoResponseCode code = BanxicoResponseCode.of(-200);
        assertThat(code).isEqualTo(BanxicoResponseCode.RECHAZO_POR_PLD);
        assertThat(code.isTerminal()).isTrue();
        assertThat(BanxicoResponseCode.isAccepted(-200)).isFalse();
    }

    @Test
    @DisplayName("Un id positivo es aceptación; cero o negativo es rechazo")
    void acceptanceIsBySign() {
        assertThat(BanxicoResponseCode.isAccepted(20250811000123L)).isTrue();
        assertThat(BanxicoResponseCode.isAccepted(0)).isFalse();
        assertThat(BanxicoResponseCode.isAccepted(-1)).isFalse();
    }

    @Test
    @DisplayName("-1 CLAVE_RASTREO_DUPLICADA es éxito idempotente, no error")
    void duplicateTrackingKeyIsIdempotentSuccess() {
        assertThat(BanxicoResponseCode.of(-1).isAlreadyRegistered()).isTrue();
        assertThat(BanxicoResponseCode.of(-2).isAlreadyRegistered()).isTrue();
    }

    @Test
    @DisplayName("-30 ENLACE_FINANCIERO_MODO_CONSULTAS se reintenta, no se rechaza")
    void transientRejectionIsRetryable() {
        assertThat(BanxicoResponseCode.of(-30).isRetryable()).isTrue();
        assertThat(BanxicoResponseCode.of(-30).isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Un código fuera del catálogo falla cerrado: UNKNOWN y terminal")
    void unknownCodeFailsClosed() {
        assertThat(BanxicoResponseCode.of(-999)).isEqualTo(BanxicoResponseCode.UNKNOWN);
        assertThat(BanxicoResponseCode.of(-4)).isEqualTo(BanxicoResponseCode.UNKNOWN);   // hueco real
        assertThat(BanxicoResponseCode.UNKNOWN.isTerminal()).isTrue();
    }
}
