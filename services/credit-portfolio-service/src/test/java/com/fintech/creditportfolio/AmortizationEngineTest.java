package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.domain.Installment;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmortizationEngineTest {

    final AmortizationEngine engine = new AmortizationEngine();
    final UUID scheduleId = UUID.randomUUID();

    @Test
    void generates_correct_number_of_installments() {
        List<Installment> schedule = engine.generateFrench(
                scheduleId, new BigDecimal("50000"), new BigDecimal("0.24"), 12,
                LocalDate.now().plusMonths(1));
        assertThat(schedule).hasSize(12);
    }

    @Test
    void installments_are_numbered_sequentially() {
        List<Installment> schedule = engine.generateFrench(
                scheduleId, new BigDecimal("10000"), new BigDecimal("12.0"), 6,
                LocalDate.now().plusMonths(1));
        for (int i = 0; i < schedule.size(); i++) {
            assertThat(schedule.get(i).getInstallmentNumber()).isEqualTo(i + 1);
        }
    }

    @Test
    void total_principal_paid_equals_loan_amount() {
        BigDecimal principal = new BigDecimal("50000.00");
        List<Installment> schedule = engine.generateFrench(
                scheduleId, principal, new BigDecimal("0.24"), 12,
                LocalDate.now().plusMonths(1));

        BigDecimal totalPrincipal = schedule.stream()
                .map(Installment::getPrincipalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Allow 1 cent rounding difference due to last installment adjustment
        assertThat(totalPrincipal.subtract(principal).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void interest_amounts_are_positive() {
        List<Installment> schedule = engine.generateFrench(
                scheduleId, new BigDecimal("50000"), new BigDecimal("0.24"), 12,
                LocalDate.now().plusMonths(1));
        schedule.forEach(i -> assertThat(i.getInterestAmount()).isPositive());
    }

    /** El pago de la cuota es capital + interés + el IVA que se traslada sobre ese interés. */
    @Test
    void total_amount_equals_principal_plus_interest_plus_tax() {
        List<Installment> schedule = engine.generateFrench(
                scheduleId, new BigDecimal("20000"), new BigDecimal("0.18"), 6,
                LocalDate.now().plusMonths(1));
        for (Installment i : schedule) {
            BigDecimal expected = i.getPrincipalAmount().add(i.getInterestAmount())
                    .add(i.getTaxAmount())
                    .setScale(2, RoundingMode.HALF_UP);
            assertThat(i.getTotalAmount()).isEqualByComparingTo(expected);
        }
    }

    @Test
    void due_dates_are_monthly_increments() {
        LocalDate first = LocalDate.of(2026, 7, 1);
        List<Installment> schedule = engine.generateFrench(
                scheduleId, new BigDecimal("30000"), new BigDecimal("12.0"), 3, first);
        assertThat(schedule.get(0).getDueDate()).isEqualTo(first);
        assertThat(schedule.get(1).getDueDate()).isEqualTo(first.plusMonths(1));
        assertThat(schedule.get(2).getDueDate()).isEqualTo(first.plusMonths(2));
    }

    @Test
    void all_installments_have_pending_status() {
        List<Installment> schedule = engine.generateFrench(
                scheduleId, new BigDecimal("10000"), new BigDecimal("12.0"), 3,
                LocalDate.now().plusMonths(1));
        schedule.forEach(i ->
                assertThat(i.getStatus().name()).isEqualTo("PENDING"));
    }

    // ── Cadencia quincenal (BIWEEKLY) — colocaciones de línea de distribuidora ────────────────

    @Test
    void biweekly_schedule_advances_every_fourteen_days() {
        LocalDate start = LocalDate.of(2026, 8, 17);
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("18000"), new BigDecimal("0.289"), 24,
                "FRENCH", "BIWEEKLY",
                AmortizationEngine.firstDueDate(start, "BIWEEKLY"),
                BigDecimal.ZERO);

        assertThat(schedule).hasSize(24);
        // Primer vencimiento a los 14 días, no al mes: es lo que corre todo el plan.
        assertThat(schedule.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(schedule.get(1).getDueDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        // 24 cuotas quincenales terminan en ~11 meses, no en 24.
        assertThat(schedule.get(23).getDueDate()).isEqualTo(LocalDate.of(2027, 7, 19));
    }

    @Test
    void firstDueDate_follows_the_cadence_not_the_calendar_month() {
        LocalDate start = LocalDate.of(2026, 8, 17);
        assertThat(AmortizationEngine.firstDueDate(start, "BIWEEKLY")).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(AmortizationEngine.firstDueDate(start, "WEEKLY")).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(AmortizationEngine.firstDueDate(start, "MONTHLY")).isEqualTo(LocalDate.of(2026, 9, 17));
        // Una cadencia desconocida cae a mensual, que es el comportamiento previo.
        assertThat(AmortizationEngine.firstDueDate(start, null)).isEqualTo(LocalDate.of(2026, 9, 17));
    }

    @Test
    void biweekly_is_twentysix_periods_a_year_not_twentyfour() {
        // Deja fijado el supuesto que decide la cuota: BIWEEKLY es cada 14 días (26/año), no
        // semimensual (24/año, día 15 y último). El $868.06 del contrato de la app sale de
        // dividir la tasa entre 24; con 26 el pago de esta misma colocación es ~$858.97.
        List<Installment> schedule = engine.generate(
                scheduleId, new BigDecimal("18000"), new BigDecimal("0.289"), 24,
                "FRENCH", "BIWEEKLY", LocalDate.of(2026, 8, 31), BigDecimal.ZERO);

        assertThat(schedule.get(0).getTotalAmount())
                .isCloseTo(new BigDecimal("858.97"), org.assertj.core.data.Offset.offset(new BigDecimal("0.50")));
    }
}
