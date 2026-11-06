package com.fintech.banking.treasury;

import com.fintech.banking.application.port.out.CashMovementPublisher;
import com.fintech.banking.application.port.out.FundingEventPublisher;
import com.fintech.banking.application.service.FundingService;
import com.fintech.banking.application.service.FundingService.CapitalCommand;
import com.fintech.banking.application.service.FundingService.CreditLineCommand;
import com.fintech.banking.application.service.FundingService.MovementCommand;
import com.fintech.banking.application.service.TreasurySelfCheckService;
import com.fintech.banking.domain.FundingMovement.Type;
import com.fintech.banking.domain.FundingSource;
import com.fintech.banking.domain.FundingSource.CounterpartyType;
import com.fintech.banking.infrastructure.adapter.in.messaging.PayoutTraceListener;
import com.fintech.banking.infrastructure.adapter.in.messaging.StpOrderSettledListener;
import com.fintech.shared.selfcheck.SelfCheck;
import com.fintech.shared.selfcheck.SelfCheckReport;
import com.fintech.shared.testing.AbstractIntegrationTest;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury checks itself against a real Postgres: a month of funding and a payout pass; each figure broken by hand is
 * caught by its check; a payout stopped days ago is reported without stopping anyone. Plus how long it takes.
 */
@SpringBootTest
class TreasurySelfChecksIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("banking"); }

    /** The seeded accounts and routes survive the truncation. */
    @Override protected List<String> preserve() { return List.of("bank_accounts", "payout_routes"); }

    private static final UUID ACCOUNT = UUID.fromString("9b1d0000-0000-4000-8000-000000000001");

    @Autowired FundingService funding;
    @Autowired TreasurySelfCheckService selfChecks;
    @Autowired PayoutTraceListener payouts;
    @Autowired StpOrderSettledListener settlements;
    @Autowired BusinessClock clock;
    /** Kafka does not take part: what is tested is what treasury holds. */
    @MockitoBean CashMovementPublisher cashMovements;
    @MockitoBean FundingEventPublisher fundingEvents;

    @AfterEach
    void realClock() {
        clock.resetShift();
    }

    private static List<String> failures(SelfCheckReport r) {
        return r.failures().stream().map(SelfCheck::code).toList();
    }

    private void aMonth() {
        clock.shiftTo(LocalDateTime.of(2026, 3, 2, 10, 0));
        FundingSource capital = funding.registerCapital(new CapitalCommand("Accionistas fundadores", "KHO200101AB1",
                "ACTA-AGE-2026-02-15", ACCOUNT, new BigDecimal("1500000"), LocalDate.of(2026, 2, 15)), "tesoreria");
        funding.record(capital.getId(), new MovementCommand(Type.CONTRIBUTION, new BigDecimal("1500000"),
                LocalDate.of(2026, 3, 2), "SPEI-CAP-1"), "tesoreria");
        FundingSource line = funding.registerCreditLine(new CreditLineCommand(CounterpartyType.DEVELOPMENT_BANK,
                "Banco de Fomento de Ejemplo", "BFE850101AB1", "CONTRATO-LF-2026-001", ACCOUNT,
                new BigDecimal("2000000"), new BigDecimal("0.13"), new BigDecimal("10000"),
                LocalDate.of(2026, 3, 2), LocalDate.of(2029, 3, 2)), "tesoreria");
        funding.record(line.getId(), new MovementCommand(Type.DRAWDOWN, new BigDecimal("1000000"),
                LocalDate.of(2026, 3, 2), "SPEI-LF-1"), "tesoreria");
        // A payout of 50,000 settled on 5 March: in the cash book and in its trace.
        UUID payout = UUID.randomUUID();
        String key = "KRD2026030500000000000001";
        payouts.onPaymentRequested(new PayoutTraceListener.PaymentRequestedPayload(payout, UUID.randomUUID(),
                new BigDecimal("50000.00"), ACCOUNT, Instant.parse("2026-03-05T16:00:00Z")));
        payouts.onDisbursementAccepted(new PayoutTraceListener.DisbursementAcceptedPayload(payout, "credit-portfolio",
                "DISPOSITION", "disp-1", Map.of(), new BigDecimal("50000.00"), key, Instant.parse("2026-03-05T16:00:05Z")));
        settlements.onOrderSettled(new StpOrderSettledListener.StpOrderSettledPayload("settled-" + payout, payout,
                UUID.randomUUID(), key, new BigDecimal("50000.00"), LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 5),
                Instant.parse("2026-03-05T16:00:30Z"), Instant.parse("2026-03-05T16:01:00Z")));
        // The month closes: 30 days on 1,000,000 at 13 % = 10,833.33, paid the same day.
        clock.shiftTo(LocalDateTime.of(2026, 3, 31, 18, 0));
        funding.accrueAll(LocalDate.of(2026, 3, 31), "cierre");
        funding.record(line.getId(), new MovementCommand(Type.INTEREST_PAYMENT, new BigDecimal("10833.33"),
                LocalDate.of(2026, 3, 31), "SPEI-INT-03"), "tesoreria");
    }

    @Test
    @DisplayName("un mes real pasa; cada cifra rota a mano la atrapa su comprobación")
    void eachBrokenFigureIsCaught() {
        aMonth();
        SelfCheckReport clean = selfChecks.check();
        assertThat(failures(clean)).as("%s", clean.checks()).isEmpty();

        jdbcTemplate.update("UPDATE banking.funding_sources SET principal = principal + 1 WHERE kind = 'CREDIT_LINE'");
        assertThat(failures(selfChecks.check())).containsExactly("FONDEO_CUADRA");
        jdbcTemplate.update("UPDATE banking.funding_sources SET principal = principal - 1 WHERE kind = 'CREDIT_LINE'");

        // A window that starts a day late: 29 days are not what the 30 of the month give.
        jdbcTemplate.update("UPDATE banking.funding_movements SET accrued_from = accrued_from + 1 "
                + "WHERE movement_type = 'INTEREST_ACCRUAL'");
        assertThat(failures(selfChecks.check())).containsExactly("INTERES_DEL_FONDEO");
        jdbcTemplate.update("UPDATE banking.funding_movements SET accrued_from = accrued_from - 1 "
                + "WHERE movement_type = 'INTEREST_ACCRUAL'");

        jdbcTemplate.update("UPDATE banking.movement_trace SET amount = amount + 1");
        assertThat(failures(selfChecks.check())).containsExactly("PAGOS_EN_CAJA_Y_TRAZA");
        jdbcTemplate.update("UPDATE banking.movement_trace SET amount = amount - 1");

        jdbcTemplate.update("""
                INSERT INTO banking.internal_movements (movement_type, reference, amount, business_date, direction,
                       source_event_id)
                VALUES ('PAYMENT_RETURN', 'devolucion-grande', 5000000, DATE '2026-03-03', 'DEBIT', 'evt-negativo')""");
        assertThat(failures(selfChecks.check())).containsExactly("CAJA_NO_NEGATIVA");
        jdbcTemplate.update("DELETE FROM banking.internal_movements WHERE source_event_id = 'evt-negativo'");

        // A settled order the trace never heard of: the cash book holds it alone.
        jdbcTemplate.update("DELETE FROM banking.movement_trace");
        assertThat(failures(selfChecks.check())).containsExactly("PAGOS_EN_CAJA_Y_TRAZA");
    }

    @Test
    @DisplayName("una orden devuelta antes de liquidarse no salió: no se busca en caja; una devuelta ya liquidada sí")
    void aPayoutReturnedBeforeSettlingNeverLeft() {
        aMonth();
        // Returned by the receiving bank before it settled (D63, E47): no movement in the cash book, and none expected.
        UUID unsettled = UUID.randomUUID();
        payouts.onPaymentRequested(new PayoutTraceListener.PaymentRequestedPayload(unsettled, UUID.randomUUID(),
                new BigDecimal("22000.04"), ACCOUNT, Instant.parse("2026-03-06T16:00:00Z")));
        payouts.onDisbursementAccepted(new PayoutTraceListener.DisbursementAcceptedPayload(unsettled, "credit-portfolio",
                "DISPOSITION", "disp-2", Map.of(), new BigDecimal("22000.04"), "KRD2026030600000000000002",
                Instant.parse("2026-03-06T16:00:05Z")));
        payouts.onDisbursementReturned(new PayoutTraceListener.DisbursementReturnedPayload(unsettled,
                new BigDecimal("22000.04"), "99", Instant.parse("2026-03-06T16:00:40Z")));
        // Settled, then returned: the money did leave, so its order is still owed to the cash book.
        UUID settled = UUID.randomUUID();
        String key = "KRD2026030900000000000003";
        payouts.onPaymentRequested(new PayoutTraceListener.PaymentRequestedPayload(settled, UUID.randomUUID(),
                new BigDecimal("8000.00"), ACCOUNT, Instant.parse("2026-03-09T16:00:00Z")));
        payouts.onDisbursementAccepted(new PayoutTraceListener.DisbursementAcceptedPayload(settled, "credit-portfolio",
                "DISPOSITION", "disp-3", Map.of(), new BigDecimal("8000.00"), key, Instant.parse("2026-03-09T16:00:05Z")));
        settlements.onOrderSettled(new StpOrderSettledListener.StpOrderSettledPayload("settled-" + settled, settled,
                UUID.randomUUID(), key, new BigDecimal("8000.00"), LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 9),
                Instant.parse("2026-03-09T16:00:30Z"), Instant.parse("2026-03-09T16:01:00Z")));
        payouts.onDisbursementReturned(new PayoutTraceListener.DisbursementReturnedPayload(settled,
                new BigDecimal("8000.00"), "01", Instant.parse("2026-03-10T10:00:00Z")));
        assertThat(jdbcTemplate.queryForList("SELECT status FROM banking.movement_trace WHERE payout_id IN (?, ?)",
                String.class, unsettled, settled)).containsOnly("RETURNED");

        SelfCheckReport r = selfChecks.check();

        assertThat(failures(r)).as("%s", r.checks()).isEmpty();
        assertThat(r.checks().stream().filter(c -> c.code().equals("PAGOS_EN_CAJA_Y_TRAZA")).findFirst().orElseThrow()
                .detail()).startsWith("2 órdenes liquidadas");

        jdbcTemplate.update("DELETE FROM banking.internal_movements WHERE tracking_key = ?", key);
        SelfCheckReport broken = selfChecks.check();
        assertThat(failures(broken)).containsExactly("PAGOS_EN_CAJA_Y_TRAZA");
        assertThat(broken.checks().stream().filter(c -> c.code().equals("PAGOS_EN_CAJA_Y_TRAZA")).findFirst()
                .orElseThrow().detail()).contains(key).doesNotContain("KRD2026030600000000000002");
    }

    @Test
    @DisplayName("un pago detenido hace días se informa sin detener a nadie")
    void stoppedPayoutIsReported() {
        aMonth();
        UUID stuck = UUID.randomUUID();
        payouts.onPaymentRequested(new PayoutTraceListener.PaymentRequestedPayload(stuck, UUID.randomUUID(),
                new BigDecimal("963.10"), ACCOUNT, Instant.parse("2026-03-10T16:00:00Z")));
        payouts.onDisbursementAccepted(new PayoutTraceListener.DisbursementAcceptedPayload(stuck, "commission",
                "COMMISSION", "LOTE:x", Map.of(), new BigDecimal("963.10"), "KRD2026031000000000000006",
                Instant.parse("2026-03-10T16:00:05Z")));
        jdbcTemplate.update("UPDATE banking.movement_trace SET business_date = DATE '2026-03-10' WHERE payout_id = ?", stuck);
        clock.shiftTo(LocalDateTime.of(2026, 3, 20, 10, 0));

        SelfCheckReport r = selfChecks.check();

        assertThat(r.inOrder()).isTrue();
        SelfCheck stale = r.checks().stream().filter(c -> c.code().equals("TRAZAS_SIN_DESENLACE")).findFirst().orElseThrow();
        assertThat(stale.ok()).isFalse();
        assertThat(stale.items()).isEqualTo(1);
        assertThat(stale.amount()).isEqualByComparingTo("963.10");
    }

    @Test
    @DisplayName("rendimiento: un año de 50,000 abonos, 10,000 órdenes con su traza y 2,000 devueltas se revisa en segundos")
    void performance() {
        aMonth();
        jdbcTemplate.update("""
                INSERT INTO banking.internal_movements (movement_type, reference, amount, business_date, direction,
                       tracking_key, source_event_id)
                SELECT 'CLIENT_PAYMENT', 'abono-' || g, 1000, DATE '2026-04-01' + (g % 300), 'CREDIT',
                       'ABONO' || lpad(g::text, 20, '0'), 'evt-abono-' || g
                  FROM generate_series(1, 50000) g""");
        jdbcTemplate.update("""
                INSERT INTO banking.internal_movements (movement_type, reference, amount, business_date, direction,
                       tracking_key, source_event_id)
                SELECT 'STP_ORDER', 'orden-' || g, 2000, DATE '2026-04-01' + (g % 300), 'DEBIT',
                       'ORDEN' || lpad(g::text, 20, '0'), 'evt-orden-' || g
                  FROM generate_series(1, 10000) g""");
        jdbcTemplate.update("""
                INSERT INTO banking.movement_trace (payout_id, tracking_key, amount, business_date, status)
                SELECT md5('p' || g)::uuid, 'ORDEN' || lpad(g::text, 20, '0'), 2000, DATE '2026-04-01' + (g % 300),
                       'SETTLED'
                  FROM generate_series(1, 10000) g""");
        // A thousand returned after settling (their order is in the cash book) and a thousand returned before (never left).
        jdbcTemplate.update("""
                UPDATE banking.movement_trace SET status = 'RETURNED', settled_at = now()
                 WHERE tracking_key <= 'ORDEN' || lpad('1000', 20, '0') AND tracking_key LIKE 'ORDEN%'""");
        jdbcTemplate.update("""
                INSERT INTO banking.movement_trace (payout_id, tracking_key, amount, business_date, status)
                SELECT md5('d' || g)::uuid, 'DEVUELTA' || lpad(g::text, 20, '0'), 3000, DATE '2026-04-01' + (g % 300),
                       'RETURNED'
                  FROM generate_series(1, 1000) g""");
        clock.shiftTo(LocalDateTime.of(2027, 2, 1, 10, 0));

        long started = System.nanoTime();
        SelfCheckReport r = selfChecks.check();
        long ms = (System.nanoTime() - started) / 1_000_000;

        assertThat(failures(r)).as("%s", r.checks()).isEmpty();
        assertThat(ms).as("60,000 movimientos revisados en %d ms", ms).isLessThan(5_000);
    }
}
