package com.fintech.creditportfolio.domain;

import com.fintech.creditportfolio.domain.StatementCoverage.Obligation;
import com.fintech.creditportfolio.domain.StatementCoverage.Payment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A distributor line places while no statement of hers is unpaid past its due date: blocked from the first day late,
 * open again the same day she pays what is overdue.
 */
class PlacementGateTest {

    private static final Obligation STATEMENT = new Obligation(UUID.randomUUID(), 7, LocalDate.of(2026, 2, 21),
            LocalDate.of(2026, 3, 3), new BigDecimal("1000.00"));

    @Test
    @DisplayName("el día de la fecha límite todavía coloca; al día siguiente, sin pagar, ya no")
    void blockedFromTheFirstDayLate() {
        assertThat(PlacementGate.evaluate(List.of(STATEMENT), List.of(), LocalDate.of(2026, 3, 3)).blocked()).isFalse();
        PlacementGate gate = PlacementGate.evaluate(List.of(STATEMENT), List.of(), LocalDate.of(2026, 3, 4));
        assertThat(gate.blocked()).isTrue();
        assertThat(gate.reason()).startsWith("LINE_BLOCKED_IN_ARREARS").contains("2026-03-03").contains("1000.00");
        assertThat(gate.overdueStatements()).isEqualTo(1);
        assertThat(gate.overdueAmount()).isEqualByComparingTo("1000.00");
        assertThat(gate.oldestDueDate()).isEqualTo(LocalDate.of(2026, 3, 3));
    }

    @Test
    @DisplayName("paga lo vencido y coloca ese mismo día; un pago parcial la deja bloqueada por lo que falta")
    void openAgainTheDayShePays() {
        Payment partial = new Payment("p1", LocalDate.of(2026, 3, 9), new BigDecimal("600.00"));
        PlacementGate still = PlacementGate.evaluate(List.of(STATEMENT), List.of(partial), LocalDate.of(2026, 3, 9));
        assertThat(still.blocked()).isTrue();
        assertThat(still.overdueAmount()).isEqualByComparingTo("400.00");
        Payment rest = new Payment("COMMISSION_NETTED:p2:7", LocalDate.of(2026, 3, 9), new BigDecimal("400.00"));
        assertThat(PlacementGate.evaluate(List.of(STATEMENT), List.of(partial, rest), LocalDate.of(2026, 3, 9)))
                .isEqualTo(PlacementGate.OPEN);
    }

    @Test
    @DisplayName("un estado que aún no vence no bloquea, aunque otro ya pagado haya vencido")
    void aStatementNotDueYetDoesNotBlock() {
        Obligation next = new Obligation(UUID.randomUUID(), 8, LocalDate.of(2026, 3, 6), LocalDate.of(2026, 3, 18),
                new BigDecimal("1200.00"));
        Payment paid = new Payment("p1", LocalDate.of(2026, 3, 3), new BigDecimal("1000.00"));
        assertThat(PlacementGate.evaluate(List.of(STATEMENT, next), List.of(paid), LocalDate.of(2026, 3, 10)).blocked()).isFalse();
    }

    @Test
    @DisplayName("una quita cierra la línea para colocar, pagada o no: es su salida")
    void aQuitaClosesTheLineForGood() {
        Payment paid = new Payment("p1", LocalDate.of(2026, 3, 3), new BigDecimal("1000.00"));
        PlacementGate gate = PlacementGate.evaluate(List.of(STATEMENT), List.of(paid), LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 8));
        assertThat(gate.blocked()).isTrue();
        assertThat(gate.reason()).startsWith("LINE_CLOSED_AFTER_QUITA").contains("2026-03-08");
        assertThat(gate.overdueStatements()).isZero();
        assertThat(PlacementGate.evaluate(List.of(STATEMENT), List.of(paid), LocalDate.of(2026, 3, 10), null)).isEqualTo(PlacementGate.OPEN);
    }

    @Test
    @DisplayName("rendimiento: treinta mil evaluaciones sobre una línea con 48 estados y 48 pagos en menos de un segundo")
    void thirtyThousandEvaluationsInUnderASecond() {
        List<Obligation> statements = new java.util.ArrayList<>();
        List<Payment> payments = new java.util.ArrayList<>();
        LocalDate cutoff = LocalDate.of(2025, 1, 6);
        for (int k = 1; k <= 48; k++) {
            statements.add(new Obligation(UUID.randomUUID(), k, cutoff, cutoff.plusDays(12), new BigDecimal("1000.00")));
            payments.add(new Payment("p" + k, cutoff.plusDays(12), new BigDecimal("1000.00")));
            cutoff = cutoff.getDayOfMonth() == 6 ? cutoff.withDayOfMonth(21) : cutoff.plusMonths(1).withDayOfMonth(6);
        }
        long start = System.nanoTime();
        int open = 0;
        for (int n = 0; n < 30_000; n++) {
            if (!PlacementGate.evaluate(statements, payments, LocalDate.of(2027, 1, 20)).blocked()) open++;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(open).isEqualTo(30_000);
        assertThat(ms).as("30,000 evaluations took %d ms", ms).isLessThan(1_000);
    }
}
