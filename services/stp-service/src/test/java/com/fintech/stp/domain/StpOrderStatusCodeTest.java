package com.fintech.stp.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Una sola fuente de verdad para el mapeo de estados (cierra B8).
 *
 * <p>El legado tenía dos: una resolvía por nombre desde el webhook de estado y otra por código
 * desde la conciliación, con un {@code default -> null} que dejaba transacciones sin estado.
 */
class StpOrderStatusCodeTest {

    @Test
    @DisplayName("Los cinco códigos de liquidación resuelven a LIQUIDADA")
    void settledCodes() {
        for (String code : new String[] {"LQ", "TLQ", "CCO", "CXO", "CCE"}) {
            assertThat(StpOrderStatusCode.fromStpCode(code)).contains(StpOrderStatusCode.LIQUIDADA);
        }
    }

    @Test
    @DisplayName("D, TD y RE resuelven a DEVUELTA; CL y TCL a CANCELADA")
    void returnedAndCancelledCodes() {
        for (String code : new String[] {"D", "TD", "RE"}) {
            assertThat(StpOrderStatusCode.fromStpCode(code)).contains(StpOrderStatusCode.DEVUELTA);
        }
        for (String code : new String[] {"CL", "TCL"}) {
            assertThat(StpOrderStatusCode.fromStpCode(code)).contains(StpOrderStatusCode.CANCELADA);
        }
    }

    @Test
    @DisplayName("Un código en tránsito devuelve vacío — no se adivina el desenlace")
    void inTransitReturnsEmpty() {
        assertThat(StpOrderStatusCode.fromStpCode("XX")).isEmpty();
        assertThat(StpOrderStatusCode.fromStpCode(null)).isEmpty();
        assertThat(StpOrderStatusCode.fromStpCode("  ")).isEmpty();
    }

    @Test
    @DisplayName("Tolera espacios y minúsculas")
    void normalisesInput() {
        assertThat(StpOrderStatusCode.fromStpCode(" lq ")).contains(StpOrderStatusCode.LIQUIDADA);
    }
}
