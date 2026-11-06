package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.WalletClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a distributor promises her client per fortnight: the portfolio plan's payment for a whole first fortnight, at
 * the line's rate ÷ 24 with the interest's IVA inside. The plan of a placement made later in its fortnight asks a
 * little less, never more.
 */
@ExtendWith(MockitoExtension.class)
class PlacementQuoteTest {

    private static final BigDecimal LINE_RATE = new BigDecimal("0.95");

    @Mock PlacementLifecycleUseCase lifecycle;
    @Mock PlacementRepository placements;
    @Mock CreditPortfolioClient creditPortfolio;
    @Mock WalletClient wallet;
    @Mock PlacementInviteService inviteService;
    @Mock PartyClient party;

    private PlacementOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new PlacementOrchestrator(lifecycle, placements, creditPortfolio, wallet,
                new BeneficiaryProperties(), inviteService, party);
    }

    @Test
    @DisplayName("$10,000 a 12 quincenas al 95 %: $1,102.42 por quincena, el pago del plan de cartera con el IVA adentro")
    void theQuoteIsThePlansPaymentWithItsIva() {
        assertThat(orchestrator.fortnightlyPayment(new BigDecimal("10000"), 12, LINE_RATE)).isEqualByComparingTo("1102.42");
    }

    @Test
    @DisplayName("los extremos del producto: $1,000 a 6 quincenas y $10,000 a 24")
    void theProductsEnds() {
        BigDecimal small = orchestrator.fortnightlyPayment(new BigDecimal("1000"), 6, LINE_RATE);
        BigDecimal large = orchestrator.fortnightlyPayment(new BigDecimal("10000"), 24, LINE_RATE);
        // Six payments return the thousand and the interest with its IVA; never less than the principal.
        assertThat(small.multiply(BigDecimal.valueOf(6))).isGreaterThan(new BigDecimal("1000"));
        assertThat(small).isEqualByComparingTo("194.45");
        assertThat(large).isEqualByComparingTo("696.20");
    }

    @Test
    @DisplayName("sin tasa en la línea, la del producto por omisión (95 %)")
    void withoutTheLinesRateTheDefault() {
        assertThat(orchestrator.fortnightlyPayment(new BigDecimal("10000"), 12, null)).isEqualByComparingTo("1102.42");
    }

    @Test
    @DisplayName("rendimiento: cien mil cotizaciones del slider en menos de un segundo")
    void aHundredThousandQuotesInUnderASecond() {
        long start = System.nanoTime();
        BigDecimal last = BigDecimal.ZERO;
        for (int i = 0; i < 100_000; i++) {
            last = orchestrator.fortnightlyPayment(BigDecimal.valueOf(1_000 + (i % 19) * 500L), 6 + (i % 10) * 2, LINE_RATE);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(last.signum()).isPositive();
        assertThat(ms).as("100,000 cotizaciones tardaron " + ms + " ms").isLessThan(1_000);
    }
}
