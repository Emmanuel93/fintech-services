package com.fintech.shared.selfcheck;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelfCheckReportTest {

    private static final Instant AT = Instant.parse("2026-11-04T18:00:00Z");

    @Test
    @DisplayName("en orden cuando todo lo que debe cumplirse se cumple; lo informativo no detiene")
    void informativeDoesNotStop() {
        SelfCheckReport r = SelfCheckReport.of("commission-service", "202610", AT, List.of(
                SelfCheck.mustHold("LOTES", "Lotes", 0, BigDecimal.ZERO, "3 lotes en orden"),
                SelfCheck.informs("SIN_DESENLACE", "Lotes sin desenlace", 1, new BigDecimal("541.06"), "1 lote")));

        assertThat(r.inOrder()).isTrue();
        assertThat(r.failures()).isEmpty();
        assertThat(r.checks().get(1).ok()).as("lo informativo dice que hay algo que ver").isFalse();
    }

    @Test
    @DisplayName("con diferencias cuando una comprobación que debe cumplirse no se cumple")
    void blockingFailure() {
        SelfCheckReport r = SelfCheckReport.of("commission-service", null, AT, List.of(
                SelfCheck.mustHold("NETO", "Neto de cada lote", 2, new BigDecimal("0.03"), "2 lotes")));

        assertThat(r.outcome()).isEqualTo(SelfCheckReport.DIFFERENCES);
        assertThat(r.scope()).isEqualTo(SelfCheckReport.EVERYTHING);
        assertThat(r.failures()).extracting(SelfCheck::code).containsExactly("NETO");
    }
}
