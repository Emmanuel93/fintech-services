package com.fintech.creditportfolio.domain.beneficiary;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ClientLimitsTest {

    static final ClientLimits NUEVO = new ClientLimits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16);

    @Test
    void aPlacementInsideEveryLimitPasses() {
        assertThat(NUEVO.rejections(ClientType.NUEVO, new BigDecimal("5000"), 16, 0, BigDecimal.ZERO)).isEmpty();
        assertThat(ClientLimits.NONE.rejections(ClientType.NUEVO, new BigDecimal("99999"), 99, 9, new BigDecimal("99999"))).isEmpty();
    }

    @Test
    void eachLimitHasItsOwnCode_andTheDetailInSpanish() {
        assertThat(NUEVO.rejections(ClientType.NUEVO, new BigDecimal("8000"), 18, 1, new BigDecimal("1200")))
                .containsExactly(
                        "AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 5000 por préstamo, pidió 8000",
                        "CLIENT_ACTIVE_LOANS_LIMIT: cliente NUEVO hasta 1 préstamos vivos, ya tiene 1",
                        "CLIENT_OUTSTANDING_LIMIT: cliente NUEVO hasta 5000 de saldo, debe 1200 y pidió 8000",
                        "TERM_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 16 quincenas, pidió 18");
    }

    @Test
    void theOutstandingCountsWhatHeAlreadyOwesHer() {
        ClientLimits revolvente = new ClientLimits(new BigDecimal("10000"), new BigDecimal("15000"), 3, 24);
        assertThat(revolvente.rejections(ClientType.REVOLVENTE, new BigDecimal("6000"), 12, 2, new BigDecimal("9000"))).isEmpty();
        assertThat(revolvente.rejections(ClientType.REVOLVENTE, new BigDecimal("6000.01"), 12, 2, new BigDecimal("9000")))
                .singleElement().asString().startsWith("CLIENT_OUTSTANDING_LIMIT");
        assertThat(revolvente.amountHeadroom(new BigDecimal("9000"))).isEqualByComparingTo("6000");
        assertThat(revolvente.amountHeadroom(new BigDecimal("1000"))).isEqualByComparingTo("10000");
        assertThat(revolvente.amountHeadroom(new BigDecimal("20000"))).isEqualByComparingTo("0");
        assertThat(ClientLimits.NONE.amountHeadroom(BigDecimal.ZERO)).isNull();
    }
}
