package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a distributor pays in a period: the plan's principal plus the interest the line actually accrued. */
class DistributorAmountDueTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    private static PlacementLine placement(String principal, String interest, String tax) {
        UUID id = UUID.randomUUID();
        BigDecimal total = d(principal).add(d(interest)).add(d(tax));
        InstallmentLine due = new InstallmentLine(id, 9, LocalDate.of(2026, 9, 1), d(principal), d(interest), d(tax), total, "PENDING");
        return new PlacementLine(id, UUID.randomUUID(), d("50000"), Instant.parse("2026-04-15T17:00:00Z"), 12, 8, total,
                d("13366.53"), LocalDate.of(2026, 9, 1), List.of(due));
    }

    @Test
    @DisplayName("capital de las cuotas del periodo más lo devengado al corte, no el total del plan")
    void principalPlusAccrued() {
        // The plan's installment says 4,412.27 (4,258.23 principal + its own interest and IVA); the line accrued
        // 175.40 of interest and IVA up to the cutoff, more than the plan assumed.
        BigDecimal due = DistributorStatementAssembler.amountDue(List.of(placement("4258.23", "132.79", "21.25")),
                d("13775.08"), d("13599.68"));
        assertThat(due).isEqualByComparingTo("4433.63");
    }

    @Test
    @DisplayName("nunca más que el saldo al corte")
    void neverMoreThanTheBalance() {
        BigDecimal due = DistributorStatementAssembler.amountDue(List.of(placement("4373.24", "33.64", "5.38")),
                d("761.61"), d("700.00"));
        assertThat(due).isEqualByComparingTo("761.61");
    }

    @Test
    @DisplayName("sin el capital al corte (evento viejo) se piden los totales de las cuotas")
    void withoutPrincipalAtCutoffAsksForTheTotals() {
        BigDecimal due = DistributorStatementAssembler.amountDue(List.of(placement("4258.23", "132.79", "21.25")),
                d("20000"), null);
        assertThat(due).isEqualByComparingTo("4412.27");
    }

    @Test
    @DisplayName("la obligación del estado: capital que factura + cargos del periodo + lo atrasado de estados anteriores")
    void statementObligation() {
        assertThat(DistributorStatementAssembler.amountDue(d("4258.23"), d("175.40"), BigDecimal.ZERO, d("13775.08")))
                .isEqualByComparingTo("4433.63");
        assertThat(DistributorStatementAssembler.amountDue(d("4258.23"), d("175.40"), d("4412.27"), d("18000")))
                .isEqualByComparingTo("8845.90");
        assertThat(DistributorStatementAssembler.amountDue(d("4373.24"), d("12.00"), BigDecimal.ZERO, d("761.61")))
                .isEqualByComparingTo("761.61");
    }

    @Test
    @DisplayName("lo que cada cliente paga en la quincena: capital, interés e IVA de su cuota")
    void eachClientsFortnightPayment() {
        // $10,000 at 12 fortnights placed on the 10th: its first installment, 917.11 + 131.94 + 21.11.
        assertThat(DistributorStatementAssembler.paymentDueNow(placement("917.11", "131.94", "21.11")))
                .isEqualByComparingTo("1070.16");
        // Two installments in the period (one left from the previous statement): both are collected.
        UUID id = UUID.randomUUID();
        List<InstallmentLine> two = List.of(
                new InstallmentLine(id, 3, LocalDate.of(2025, 2, 15), d("950.00"), d("120.16"), d("19.23"), d("1089.39"), "PENDING"),
                new InstallmentLine(id, 4, LocalDate.of(2025, 2, 28), d("990.00"), d("85.00"), d("13.60"), d("1088.60"), "PENDING"));
        PlacementLine p = new PlacementLine(id, UUID.randomUUID(), d("10000"), Instant.parse("2025-01-10T17:00:00Z"), 12, 2,
                d("1070.16"), d("7000"), LocalDate.of(2025, 2, 28), two);
        assertThat(DistributorStatementAssembler.paymentDueNow(p)).isEqualByComparingTo("2177.99");
        // Nothing billed for the client this fortnight: nothing to collect.
        PlacementLine none = new PlacementLine(id, UUID.randomUUID(), d("10000"), Instant.parse("2025-01-10T17:00:00Z"), 12, 2,
                d("1070.16"), d("7000"), LocalDate.of(2025, 2, 28), List.of());
        assertThat(DistributorStatementAssembler.paymentDueNow(none)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("rendimiento: los pagos de quincena de diez mil colocaciones se suman en menos de medio segundo")
    void tenThousandPlacementsInUnderHalfASecond() {
        List<PlacementLine> many = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) many.add(placement("917.11", "131.94", "21.11"));
        long start = System.nanoTime();
        BigDecimal total = many.stream().map(DistributorStatementAssembler::paymentDueNow).reduce(BigDecimal.ZERO, BigDecimal::add);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(total).isEqualByComparingTo("10701600.00");
        assertThat(ms).isLessThan(500);
    }
}
