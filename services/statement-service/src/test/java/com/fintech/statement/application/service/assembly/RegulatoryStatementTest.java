package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.Account;
import com.fintech.statement.application.port.out.StatementSource.Balances;
import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.port.out.StatementSource.Totals;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.support.Samples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.fintech.statement.support.Samples.money;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the statement says, beyond its figures: the next payment and its number, a settled loan's certificate, only
 * the live placements of a distributor, the overdue ones with their date, the Banxico minimum of a card, and the cost.
 */
class RegulatoryStatementTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private final UUID account = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    private AssemblyContext ctx(CutoffFacts f, SourceView v) {
        return new AssemblyContext(UUID.randomUUID(), f, v, Samples.persona(party, "Titular"), Map.of(), Optional.empty(),
                new StatementProperties(), MX, Instant.parse("2026-12-01T14:00:00Z"));
    }

    private static Account account(UUID id, UUID party, String product, String behavior, String status,
                                   String limit, String available) {
        return new Account(id, "CTR-1", product + "-V1", product, behavior, party, status, new BigDecimal("0.36"),
                new BigDecimal("0.60"), new BigDecimal("0.16"), limit == null ? null : money(limit),
                available == null ? null : money(available), money("0"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 12, "FRENCH", Instant.parse("2026-08-01T17:00:00Z"), "646180157000000004", 0);
    }

    private static Totals totals() {
        return new Totals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static Balances balances(String total) {
        return new Balances(money(total), BigDecimal.ZERO, BigDecimal.ZERO, money(total));
    }

    @Test
    @DisplayName("a loan whose installment was paid on the cutoff day shows its next payment, its number and its date")
    void nextPaymentWhenThePeriodsWasPaid() {
        // Installment 3 falls due on the cutoff (30 Nov) and is already paid: nothing is owed until the 4th.
        List<InstallmentLine> plan = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            plan.add(new InstallmentLine(account, n, LocalDate.of(2026, 8, 30).plusMonths(n), money("1666.67"),
                    money("410.00"), money("65.60"), money("2142.27"), n <= 3 ? "PAID" : "PENDING"));
        }
        SourceView v = new SourceView(account(account, party, "PERSONAL_LOAN", "INSTALLMENT", "ACTIVE", null, null),
                Samples.DESDE, Samples.CORTE, Samples.LIMITE, balances("15000"), balances("15000"), totals(), List.of(),
                plan, List.of());
        CutoffFacts f = Samples.facts(account, party, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 3, "15000", null, null);

        DocumentModel m = new InstallmentStatementAssembler().assemble(ctx(f, v)).model();

        assertThat(m.parameter("BOX1_LABEL")).isEqualTo("Tu próximo pago (4 de 12)");
        assertThat(m.parameter("BOX1_VALUE")).isEqualTo("$2,142.27");
        assertThat(m.parameter("BOX2_VALUE")).isEqualTo("30 dic 2026");
        assertThat(m.parameter("INFO3_VALUE")).isEqualTo("3 de 12");
        assertThat(m.parameter("INFO4_VALUE")).isEqualTo("Ninguno");
    }

    @Test
    @DisplayName("a settled loan's statement says so and stands as the certificate that nothing is owed")
    void settledLoan() {
        List<InstallmentLine> plan = List.of(new InstallmentLine(account, 1, LocalDate.of(2026, 11, 30), money("1000"),
                money("30"), money("4.80"), money("1034.80"), "PAID"));
        SourceView v = new SourceView(account(account, party, "PERSONAL_LOAN", "INSTALLMENT", "SETTLED", null, null),
                Samples.DESDE, Samples.CORTE, Samples.LIMITE, balances("1034.80"), balances("0"), totals(), List.of(),
                plan, List.of());
        CutoffFacts f = Samples.facts(account, party, "PERSONAL_LOAN", "INSTALLMENT", "CUSTOMER", 1, "0", null, null);

        DocumentModel m = new InstallmentStatementAssembler().assemble(ctx(f, v)).model();

        assertThat(m.parameter("BOX2_VALUE")).isEqualTo("Liquidado");
        assertThat((String) m.parameter("PRODUCT_NOTE")).contains("no tienes adeudos");
    }

    @Test
    @DisplayName("a distributor's statement lists the live placements and the one settled in the period, not the old ones")
    void onlyLivePlacements() {
        UUID live = UUID.randomUUID(), overdue = UUID.randomUUID(), settledNow = UUID.randomUUID(), settledBefore = UUID.randomUUID();
        // The live one bills its third payment; the overdue one owes three since 15 October and bills its fifth.
        List<PlacementLine> placements = List.of(
                new PlacementLine(live, UUID.randomUUID(), money("10000"), Instant.parse("2026-10-01T17:00:00Z"), 6, 2,
                        money("1800"), money("6700"), LocalDate.of(2026, 12, 15),
                        List.of(installment(live, 3, "1500.00", "258.62", "41.38")), 0, null, false, false),
                new PlacementLine(overdue, UUID.randomUUID(), money("8000"), Instant.parse("2026-09-01T17:00:00Z"), 10, 1,
                        money("900"), money("7200"), LocalDate.of(2026, 12, 15),
                        List.of(installment(overdue, 2, "700.00", "172.41", "27.59"),
                                installment(overdue, 3, "700.00", "172.41", "27.59"),
                                installment(overdue, 4, "700.00", "172.41", "27.59"),
                                installment(overdue, 5, "700.00", "172.41", "27.59")), 3,
                        LocalDate.of(2026, 10, 15), false, false),
                new PlacementLine(settledNow, UUID.randomUUID(), money("5000"), Instant.parse("2026-08-01T17:00:00Z"), 6, 6,
                        money("900"), BigDecimal.ZERO, null, List.of(), 0, null, true, false,
                        LocalDate.of(2026, 11, 20), LocalDate.of(2027, 1, 15)),
                new PlacementLine(settledBefore, UUID.randomUUID(), money("5000"), Instant.parse("2026-04-01T17:00:00Z"), 6, 6,
                        money("900"), BigDecimal.ZERO, null, List.of(), 0, null, false, false));
        SourceView v = new SourceView(account(account, party, "DISTRIBUTOR_LINE", "REVOLVING", "ACTIVE", "100000", "86100"),
                Samples.DESDE, Samples.CORTE, Samples.LIMITE, balances("14000"), balances("13900"), totals(), List.of(),
                List.of(), placements);
        CutoffFacts f = Samples.facts(account, party, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 5, "13900", null, null);

        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(f, v)).model();

        // "Vigentes" holds only what still owes; the one settled in the period has its own table, after it.
        List<Map<String, ?>> rows = m.rows().stream().filter(r -> "COLOC".equals(r.get("section"))).toList();
        assertThat(rows).hasSize(2);
        // The payment each statement bills, not how many were paid: «3 de 6», «5 de 10».
        assertThat(rows.stream().map(r -> String.valueOf(r.get("c4"))).toList())
                .containsExactly("3 de 6", "5 de 10");
        assertThat(rows.stream().map(r -> String.valueOf(r.get("c5"))).toList())
                .containsExactly("—", "3 desde 15 oct 2026");
        // What each client pays: the installments' principal, interest and IVA.
        assertThat(rows.stream().map(r -> String.valueOf(r.get("c6"))).toList())
                .containsExactly("$1,800.00", "$3,600.00");
        // Without a commission policy the column says so instead of a made-up amount.
        assertThat(rows.stream().map(r -> String.valueOf(r.get("c8"))).toList()).containsExactly("—", "—");
        List<Map<String, ?>> settled = m.rows().stream().filter(r -> "LIQ".equals(r.get("section"))).toList();
        assertThat(settled).singleElement().satisfies(r -> {
            assertThat(r.get("c3")).isEqualTo("$5,000.00");
            assertThat(r.get("c4")).isEqualTo("6 pagos");
            assertThat(r.get("c5")).isEqualTo("20 nov 2026");
            assertThat(r.get("c6")).isEqualTo("Pago anticipado");
        });
        List<String> order = m.rows().stream().map(r -> String.valueOf(r.get("section"))).distinct().toList();
        assertThat(order).startsWith("COLOC", "LIQ");
        assertThat(m.parameter("INFO3_VALUE")).isEqualTo("2");
        assertThat(m.parameter("INFO5_VALUE")).isEqualTo("1");
        assertThat(m.parameter("INFO6_VALUE")).isEqualTo("3");
        assertThat((String) m.parameter("PRODUCT_NOTE")).contains("3 pagos vencidos");
    }

    @Test
    @DisplayName("a line paid off in full: no live placements, and the ones settled listed apart, not under \"vigentes\"")
    void aLinePaidOffInFull() {
        List<PlacementLine> placements = List.of(
                new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), money("50000"), Instant.parse("2026-11-02T17:00:00Z"),
                        24, 24, money("2300"), BigDecimal.ZERO, null, List.of(), 0, null, true, false,
                        LocalDate.of(2026, 11, 20), LocalDate.of(2027, 10, 30)),
                new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), money("40000"), Instant.parse("2026-11-03T17:00:00Z"),
                        6, 6, money("7000"), BigDecimal.ZERO, null, List.of(), 0, null, true, false,
                        LocalDate.of(2026, 11, 20), LocalDate.of(2027, 1, 30)));
        SourceView v = new SourceView(account(account, party, "DISTRIBUTOR_LINE", "REVOLVING", "ACTIVE", "500000", "500000"),
                Samples.DESDE, Samples.CORTE, Samples.LIMITE, balances("90000"), balances("0"), totals(), List.of(),
                List.of(), placements);
        CutoffFacts f = Samples.facts(account, party, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 3, "0", null, null);

        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(f, v)).model();

        assertThat(m.rows().stream().filter(r -> "COLOC".equals(r.get("section"))).toList())
                .singleElement().satisfies(r -> assertThat(r.get("emphasis")).isEqualTo("EMPTY"));
        assertThat(m.rows().stream().filter(r -> "LIQ".equals(r.get("section"))).map(r -> String.valueOf(r.get("c6"))).toList())
                .containsExactly("Pago anticipado", "Pago anticipado");
        assertThat(m.parameter("INFO3_VALUE")).isEqualTo("0");
        assertThat(m.parameter("INFO5_VALUE")).isEqualTo("2");
    }

    @Test
    @DisplayName("a card's statement asks the Banxico minimum: a small balance on a big line pays 1.25 % of the line")
    void cardMinimumFloor() {
        SourceView v = new SourceView(account(account, party, "CREDIT_CARD", "REVOLVING", "ACTIVE", "30000", "25998.40"),
                Samples.DESDE, Samples.CORTE, Samples.LIMITE, balances("5000"), balances("4001.60"), totals(), List.of(),
                List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, null, null, BigDecimal.ZERO);
        // Closing sealed the product's 5 %: 200.08. The floor is 1.25 % × 30,000 = 375.
        CutoffFacts f = Samples.facts(account, party, "CREDIT_CARD", "REVOLVING", "CUSTOMER", 3, "4001.60", "4001.60", "200.08");

        Assembled doc = new RevolvingStatementAssembler().assemble(ctx(f, v));

        assertThat(doc.minimumPayment()).isEqualByComparingTo("375.00");
        assertThat(doc.model().parameter("INFO5_LABEL")).isEqualTo("Pagando sólo el mínimo liquidas en");
        assertThat((String) doc.model().parameter("INFO5_VALUE")).endsWith("meses");
        assertThat((String) doc.model().parameter("COST1_VALUE")).endsWith("% Sin IVA");
    }

    @Test
    @DisplayName("the cost: CAT of a fee-free plan, the 12-month payment and the payoff paying only the minimum")
    void creditCost() {
        // A fortnightly plan at 20 %: (1 + 0.20 / 26)^26 − 1 = 22.05 %.
        assertThat(CreditCost.catFromRate(new BigDecimal("0.20"), 26)).isBetween(new BigDecimal("0.2204"), new BigDecimal("0.2206"));
        // 12,000 at 36 % (+ IVA on interest): 3.48 % a month, 1,240.35 for 12 months.
        assertThat(CreditCost.paymentToPayOffIn(new BigDecimal("12000"), new BigDecimal("0.36"), 12))
                .isBetween(new BigDecimal("1240"), new BigDecimal("1241"));
        CreditCost.Payoff p = CreditCost.monthsPayingOnlyTheMinimum(new BigDecimal("12000"), new BigDecimal("0.36"),
                new BigDecimal("0.05"), new BigDecimal("30000"), true);
        assertThat(p.neverPaysOff()).isFalse();
        assertThat(p.months()).isBetween(24, 120);
        assertThat(p.totalPaid()).isGreaterThan(new BigDecimal("12000"));
    }

    @Test
    @DisplayName("an installment moved off a non-business day: the statement says so, and that paying on the new date is on time")
    void installmentMovedToABusinessDay() {
        // Due on Holy Thursday (2 Apr), moved to Monday 6 Apr.
        List<InstallmentLine> plan = List.of(
                new InstallmentLine(account, 1, LocalDate.of(2026, 4, 6), money("1000"), money("30"), money("4.80"),
                        money("1034.80"), "PENDING", LocalDate.of(2026, 4, 2)),
                new InstallmentLine(account, 2, LocalDate.of(2026, 5, 4), money("1000"), money("20"), money("3.20"),
                        money("1023.20"), "PENDING", LocalDate.of(2026, 5, 2)));
        assertThat(InstallmentStatementAssembler.movedNote(plan.subList(0, 1), plan.get(0)))
                .contains("Tu pago 1 se recorrió del 2 abr 2026 al 6 abr 2026")
                .contains("no genera atraso ni intereses moratorios");
        assertThat(InstallmentStatementAssembler.movedNote(List.of(), null)).isNull();
    }

    private static InstallmentLine installment(UUID schedule, int number, String principal, String interest, String tax) {
        BigDecimal total = new BigDecimal(principal).add(new BigDecimal(interest)).add(new BigDecimal(tax));
        return new InstallmentLine(schedule, number, LocalDate.of(2026, 11, 30), new BigDecimal(principal),
                new BigDecimal(interest), new BigDecimal(tax), total, "PENDING");
    }
}
