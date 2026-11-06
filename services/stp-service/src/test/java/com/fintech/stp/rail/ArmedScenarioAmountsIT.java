package com.fintech.stp.rail;

import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.RegisterPaymentOrderCommand;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.in.ManageCompanyUseCase;
import com.fintech.stp.application.port.in.PollSettlementsUseCase;
import com.fintech.stp.application.port.in.RegisterPaymentOrderUseCase;
import com.fintech.stp.application.port.in.RelayOutboxUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpEventPublisher;
import com.fintech.stp.application.port.out.StpPaymentOrderRepository;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.StpPaymentOrder;
import com.fintech.stp.domain.StpPaymentOrderStatus;
import com.fintech.stp.infrastructure.adapter.out.http.stub.StubScenarioAmounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local stack's stub plays a scenario only for the amounts a test armed, with the real relay, poller, database
 * and clock. The platform computes amounts of its own (a commission lot, a funding payment) and the distributor suite
 * of 2026-11-04 lost three lots to the cents: $754.10 timed out every month, $721.05 was cancelled twice and $529.06
 * was accepted and never settled. No test had asked for them.
 */
@SpringBootTest
@ActiveProfiles("test")
class ArmedScenarioAmountsIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("stp"); }
    /** The bank calendar is a catalog seeded by Liquibase (015); truncating it would date orders on holidays. */
    @Override protected List<String> preserve() { return List.of("business_calendar_days"); }

    /** A Tuesday with no holiday near. */
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    @Autowired RegisterPaymentOrderUseCase registrar;
    @Autowired RelayOutboxUseCase relay;
    @Autowired PollSettlementsUseCase poller;
    @Autowired StpPaymentOrderRepository orders;
    @Autowired StpCompanyRepository companies;
    @Autowired ManageCompanyUseCase admin;
    @Autowired BusinessClock businessClock;
    @Autowired StpProperties properties;
    @Autowired StubScenarioAmounts scenarioAmounts;
    @MockitoBean StpEventPublisher publisher;

    private UUID companyId;

    @BeforeEach
    void company() throws Exception {
        companyId = companies.save(StpCompany.create("DEMO-" + UUID.randomUUID().toString().substring(0, 8), "FINTECH_SA",
                90646, "CR", "646", "180", "0000")).getCompanyId();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Instant now = Instant.now();
        admin.registerKey(companyId, "it", KeyPurpose.SIGNING,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)), "it");
        properties.getGateway().getStub().setScenarioAmounts(StpProperties.ScenarioAmounts.ARMED);
    }

    @AfterEach
    void reset() {
        properties.getGateway().getStub().setScenarioAmounts(StpProperties.ScenarioAmounts.ANY);
        scenarioAmounts.arm(List.of());
        businessClock.resetShift();
    }

    @Test
    @DisplayName("armado: los importes que calcula la plataforma se liquidan, tengan los centavos que tengan")
    void computedAmountsSettleWhateverTheirCents() {
        for (String amount : List.of("754.10", "721.05", "529.06")) {
            StpPaymentOrder order = relayUntilSettledDown(register(amount));
            assertThat(order.status()).as(amount).isEqualTo(StpPaymentOrderStatus.ACCEPTED);
            pollAt(LocalTime.of(12, 0));
            assertThat(reload(order).status()).as(amount).isEqualTo(StpPaymentOrderStatus.SETTLED);
        }
    }

    @Test
    @DisplayName("armado: un importe armado juega su escenario — PLD rechaza y el banco devuelve")
    void anArmedAmountPlaysItsScenario() {
        scenarioAmounts.arm(List.of(new BigDecimal("4000.02"), new BigDecimal("3000.04")));

        StpPaymentOrder pld = relayUntilSettledDown(register("4000.02"));
        assertThat(pld.status()).isEqualTo(StpPaymentOrderStatus.REJECTED);

        StpPaymentOrder returned = relayUntilSettledDown(register("3000.04"));
        assertThat(returned.status()).isEqualTo(StpPaymentOrderStatus.ACCEPTED);
        pollAt(LocalTime.of(12, 0));
        assertThat(reload(returned).status()).isEqualTo(StpPaymentOrderStatus.RETURNED);
    }

    @Test
    @DisplayName("armar reemplaza lo armado: lo de una corrida anterior no se queda")
    void armingReplacesWhatWasArmed() {
        scenarioAmounts.arm(List.of(new BigDecimal("4000.02")));
        assertThat(scenarioAmounts.arm(List.of(new BigDecimal("3000.04"), new BigDecimal("3000.040"))))
                .containsExactly(new BigDecimal("3000.04"));
        assertThat(scenarioAmounts.isArmed(new BigDecimal("4000.02"))).isFalse();

        StpPaymentOrder order = relayUntilSettledDown(register("4000.02"));
        assertThat(order.status()).isEqualTo(StpPaymentOrderStatus.ACCEPTED);
    }

    @Test
    @DisplayName("sin armar (ANY), los centavos eligen para todo importe, como en las pruebas del conector")
    void withAnyTheCentsPickForEveryAmount() {
        properties.getGateway().getStub().setScenarioAmounts(StpProperties.ScenarioAmounts.ANY);
        StpPaymentOrder order = relayUntilSettledDown(register("1000.02"));
        assertThat(order.status()).isEqualTo(StpPaymentOrderStatus.REJECTED);
    }

    @Test
    @DisplayName("rendimiento: 2,000 consultas al conjunto armado en menos de tres segundos")
    void twoThousandLookupsInUnderThreeSeconds() {
        scenarioAmounts.arm(IntStream.range(0, 500).mapToObj(i -> BigDecimal.valueOf(100_002L + 100L * i, 2)).toList());
        long start = System.nanoTime();
        int armed = 0;
        for (int i = 0; i < 2_000; i++) {
            if (scenarioAmounts.isArmed(BigDecimal.valueOf(100_002L + 100L * (i % 1_000), 2))) armed++;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(armed).isEqualTo(1_000);
        assertThat(ms).as("2,000 consultas tardaron " + ms + " ms").isLessThan(3_000);
    }

    private StpPaymentOrder register(String amount) {
        businessClock.shiftTo(DAY.atTime(10, 0));
        UUID request = UUID.randomUUID();
        registrar.register(new RegisterPaymentOrderCommand(request, companyId, new BigDecimal(amount), "MXN",
                "JUAN PEREZ", "646180157000000004", "40", "PEPJ800101ABC", 646,
                "Comisiones 202611 lote it", 1L, null, "corr-1",
                UUID.randomUUID(), "646180000000000012", "FINTECH SA DE CV", "FSE200101AB1", "CLI-001"));
        return orders.findByPaymentRequestId(request).orElseThrow();
    }

    /** Runs the relay as its job would, letting each backoff pass, until the order no longer waits to be sent. */
    private StpPaymentOrder relayUntilSettledDown(StpPaymentOrder order) {
        LocalDateTime at = DAY.atTime(10, 0);
        for (int i = 0; i < 12; i++) {
            businessClock.shiftTo(at);
            relay.relayPending();
            StpPaymentOrder now = reload(order);
            if (now.status() != StpPaymentOrderStatus.PENDING) {
                return now;
            }
            at = at.plusMinutes(30);
        }
        throw new AssertionError("la orden sigue esperando su envío");
    }

    private StpPaymentOrder reload(StpPaymentOrder order) {
        return orders.findById(order.getStpPaymentOrderId()).orElseThrow();
    }

    private void pollAt(LocalTime time) {
        businessClock.shiftTo(DAY.atTime(time));
        poller.pollInFlightOrders();
    }
}
