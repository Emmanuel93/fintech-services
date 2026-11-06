package com.fintech.statement.application.service.assembly;

import com.fintech.statement.support.Samples;
import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.EarnedCommission;
import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a distributor reads on her statement: the payment each client owes this fortnight («3 de 6», not how many were
 * paid), her 18 % commission row by row and in its own table, how the total to pay is formed, and the CAT as the
 * Circular 21/2009 prints it («153.9 % Sin IVA», not «1.5 %»).
 */
class DistributorCommissionStatementTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final BigDecimal SHARE = new BigDecimal("0.18");
    private final UUID account = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    @Test
    @DisplayName("el CAT se imprime como porcentaje con un decimal y «Sin IVA», también arriba de 100 %")
    void theCatPrintsAsAPercentageAboveOneHundredToo() {
        assertThat(StatementAssembler.catText(new BigDecimal("1.538762"))).isEqualTo("153.9 % Sin IVA");
        assertThat(StatementAssembler.catText(new BigDecimal("0.253400"))).isEqualTo("25.3 % Sin IVA");
        assertThat(StatementAssembler.catText(new BigDecimal("1.000000"))).isEqualTo("100.0 % Sin IVA");
        assertThat(StatementAssembler.catText(null)).isEqualTo("No disponible");
    }

    @Test
    @DisplayName("cada colocación dice el pago que se cobra, lo que paga el cliente, la comisión y su CAT")
    void eachPlacementSaysThePaymentBilledItsCommissionAndItsCat() {
        DocumentModel m = assemble(Samples.distribuidora(account, party, clients(5)), commission(List.of()));

        List<Map<String, Object>> rows = rows(m, "COLOC");
        assertThat(rows).hasSize(5).allSatisfy(r -> {
            assertThat(r.get("c4")).isEqualTo("3 de 6");
            assertThat(r.get("c6")).isEqualTo("$995.73");
            // 18 % of the installment's 140.00 of interest.
            assertThat(r.get("c8")).isEqualTo("$25.20");
            // The sample's view does not carry the plan's CAT: the rate's, 48 % by fortnights.
            assertThat(r.get("c9")).isEqualTo("60.8 %");
        });
    }

    @Test
    @DisplayName("una colocación nueva cobra su pago 1, no «0 de 8»; colocada el día del corte no cobra nada aún")
    void aNewPlacementBillsItsFirstPayment() {
        PlacementLine nueva = placement(8, 0, List.of(installment(1, "250.00", "40.00", "6.40")), null);
        PlacementLine delCorte = placement(8, 0, List.of(), null);

        assertThat(DistributorStatementAssembler.paymentNumber(nueva)).isEqualTo("1 de 8");
        assertThat(DistributorStatementAssembler.paymentNumber(delCorte)).isEqualTo("—");
    }

    @Test
    @DisplayName("el CAT de cada colocación es el de su plan cuando cartera lo trae")
    void thePlacementsOwnCatWhenThePortfolioSendsIt() {
        PlacementLine conCat = placement(12, 2, List.of(installment(3, "800.00", "300.00", "48.00")),
                new BigDecimal("1.540317"));
        SourceView v = view(List.of(conCat), "1148.00", "348.00");

        DocumentModel m = assemble(v, commission(List.of()));
        assertThat(rows(m, "COLOC")).singleElement().satisfies(r -> assertThat(r.get("c9")).isEqualTo("154.0 %"));
    }

    @Test
    @DisplayName("al corriente, los pagos de los clientes son el total a pagar: no hay diferencia que explicar")
    void whenCurrentTheClientsPaymentsAreTheTotal() {
        // Two placements bill 1,148.00 and 574.00; the line charged their interest and IVA, nothing else.
        SourceView v = view(List.of(
                placement(12, 2, List.of(installment(3, "800.00", "300.00", "48.00")), null),
                placement(6, 1, List.of(installment(2, "400.00", "150.00", "24.00")), null)),
                "1200.00", "522.00");

        DocumentModel m = assemble(v, commission(List.of()));
        List<Map<String, Object>> pago = rows(m, "PAGO");
        assertThat(pago).extracting(r -> r.get("c1")).containsExactly(
                "Pagos de tus clientes en esta quincena (la suma de la columna «A pagar»)", "Total a pagar en el periodo");
        assertThat(pago).extracting(r -> r.get("c2")).containsExactly("$1,722.00", "$1,722.00");
    }

    @Test
    @DisplayName("en atraso, el total suma a los pagos de los clientes los moratorios y lo pendiente")
    void inArrearsTheTotalAddsTheMoratoriumAndWhatIsPending() {
        SourceView v = view(List.of(placement(12, 2, List.of(installment(3, "800.00", "300.00", "48.00")), null)),
                "800.00", "348.00", "130.50");

        List<Map<String, Object>> pago = rows(assemble(v, commission(List.of())), "PAGO");
        assertThat(pago).extracting(r -> r.get("c2")).containsExactly("$1,148.00", "$130.50", "$1,278.50");
        assertThat(pago.get(1).get("c1")).isEqualTo("(+) Moratorios, comisiones y cargos pendientes de cortes anteriores");
    }

    @Test
    @DisplayName("su comisión: la de este estado, la ganada en el periodo con su IVA y la que falta depositar")
    void herCommissionInItsOwnTable() {
        List<EarnedCommission> earned = List.of(
                // Earned in the period (1–30 November) on two payments; one of them already paid out.
                new EarnedCommission(LocalDate.of(2026, 11, 3), new BigDecimal("500.00"), SHARE, new BigDecimal("90.00"),
                        new BigDecimal("14.40"), "LIQUIDATED", "202611"),
                new EarnedCommission(LocalDate.of(2026, 11, 18), new BigDecimal("400.00"), SHARE, new BigDecimal("72.00"),
                        new BigDecimal("11.52"), "ACCRUED", "202611"),
                // Before the period, still waiting for its lot; and one reversed, which counts for nothing.
                new EarnedCommission(LocalDate.of(2026, 10, 20), new BigDecimal("100.00"), SHARE, new BigDecimal("18.00"),
                        new BigDecimal("2.88"), "ACCRUED", "202610"),
                new EarnedCommission(LocalDate.of(2026, 11, 20), new BigDecimal("50.00"), SHARE, new BigDecimal("9.00"),
                        new BigDecimal("1.44"), "REVERSED", "202611"));
        DocumentModel m = assemble(Samples.distribuidora(account, party, clients(5)), commission(earned));

        List<Map<String, Object>> tabla = rows(m, "TUCOM");
        assertThat(tabla).hasSize(4);
        // This statement: 18 % of the 5 × 140.00 of interest its payments carry.
        assertThat(tabla.get(0)).containsEntry("c2", "$700.00").containsEntry("c3", "18 %").containsEntry("c4", "$126.00");
        // Earned in the period: 90.00 + 72.00 on 900.00 of interest, and their IVA.
        assertThat(tabla.get(1)).containsEntry("c2", "$900.00").containsEntry("c4", "$162.00");
        assertThat(tabla.get(2)).containsEntry("c4", "$25.92");
        // Waiting to be paid out at the cutoff: 72.00 + 18.00.
        assertThat(tabla.get(3)).containsEntry("c4", "$90.00");
        assertThat((String) m.parameter("PRODUCT_NOTE")).contains("Ganas el 18 % del interés que pagan tus clientes");
    }

    @Test
    @DisplayName("la columna «Tu comisión» suma al centavo la comisión del estado: D01, 21 de marzo de 2025")
    void theCommissionColumnAddsUpToTheStatementsCommission() {
        // Rounded row by row: 25.66 + 7.44 + 63.60 + 5.23 = 101.93, against 18 % of 566.24 = 101.92.
        List<BigDecimal> interests = List.of(new BigDecimal("142.56"), new BigDecimal("41.33"), new BigDecimal("353.33"),
                new BigDecimal("29.02"));

        List<BigDecimal> rows = DistributorStatementAssembler.commissionsByRow(interests, SHARE);

        assertThat(rows).extracting(BigDecimal::toPlainString).containsExactly("25.66", "7.44", "63.60", "5.22");
        assertThat(rows.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("101.92");
    }

    @Test
    @DisplayName("en el estado, la columna «Tu comisión» suma lo que dice la tabla de la comisión de este estado")
    void onTheStatementTheColumnAddsUpToTheTable() {
        SourceView v = view(List.of(
                placement(12, 4, List.of(installment(5, "405.42", "142.56", "22.81")), null),
                placement(8, 4, List.of(installment(5, "243.95", "41.33", "6.61")), null),
                placement(24, 3, List.of(installment(4, "261.50", "353.33", "56.53")), null),
                placement(6, 0, List.of(installment(1, "158.52", "29.02", "4.64")), null)),
                "1069.39", "656.83");

        DocumentModel m = assemble(v, commission(List.of()));
        BigDecimal column = rows(m, "COLOC").stream()
                .map(r -> new BigDecimal(((String) r.get("c8")).replace("$", "").replace(",", "")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(column).isEqualByComparingTo("101.92");
        assertThat(rows(m, "TUCOM").get(0)).containsEntry("c2", "$566.24").containsEntry("c4", "$101.92");
    }

    @Test
    @DisplayName("la comisión de cada fila nunca se aleja más de un centavo de la suya, y en cero no inventa nada")
    void eachRowStaysWithinACentOfItsOwnShare() {
        List<BigDecimal> interests = IntStream.range(0, 37)
                .mapToObj(i -> new BigDecimal(i * 13.37 % 400).setScale(2, RoundingMode.HALF_UP)).toList();

        List<BigDecimal> rows = DistributorStatementAssembler.commissionsByRow(interests, SHARE);

        BigDecimal total = DistributorStatementAssembler.commissionOn(
                interests.stream().reduce(BigDecimal.ZERO, BigDecimal::add), SHARE);
        assertThat(rows.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
        for (int i = 0; i < interests.size(); i++) {
            BigDecimal exact = interests.get(i).multiply(SHARE);
            assertThat(rows.get(i).subtract(exact).abs()).isLessThan(new BigDecimal("0.01"));
        }
        assertThat(DistributorStatementAssembler.commissionsByRow(List.of(BigDecimal.ZERO, BigDecimal.ZERO), SHARE))
                .extracting(BigDecimal::toPlainString).containsExactly("0.00", "0.00");
        assertThat(DistributorStatementAssembler.commissionsByRow(List.of(), SHARE)).isEmpty();
    }

    @Test
    @DisplayName("rendimiento: repartir la comisión de 1,000 estados de 200 filas tarda menos de un segundo")
    void apportioningAThousandStatementsInUnderASecond() {
        List<BigDecimal> interests = IntStream.range(0, 200)
                .mapToObj(i -> new BigDecimal(17 + (i * 7.31) % 300).setScale(2, RoundingMode.HALF_UP)).toList();
        long start = System.nanoTime();
        List<BigDecimal> last = List.of();
        for (int i = 0; i < 1_000; i++) {
            last = DistributorStatementAssembler.commissionsByRow(interests, SHARE);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(last).hasSize(200);
        assertThat(ms).as("1,000 repartos de 200 filas tardaron %d ms", ms).isLessThan(1_000);
    }

    @Test
    @DisplayName("sin política de comisión, la tabla lo dice y la columna no inventa montos")
    void withoutAPolicyTheTableSaysSo() {
        DocumentModel m = assemble(Samples.distribuidora(account, party, clients(2)), DistributorCommission.NONE);
        assertThat(rows(m, "COLOC")).allSatisfy(r -> assertThat(r.get("c8")).isEqualTo("—"));
        assertThat(m.rows().stream().filter(r -> "TUCOM".equals(r.get("section"))).toList())
                .singleElement().satisfies(r -> assertThat(r.get("emphasis")).isEqualTo("EMPTY"));
    }

    @Test
    @DisplayName("rendimiento: un estado de 200 colocaciones se arma 200 veces en menos de dos segundos")
    void twoHundredPlacementsTwoHundredTimesInUnderTwoSeconds() {
        List<PlacementLine> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(placement(12, i % 12, List.of(installment(1 + i % 12, "800.00", "300.00", "48.00")), null));
        }
        SourceView v = view(many, "160000.00", "69600.00");
        List<EarnedCommission> earned = IntStream.range(0, 400).mapToObj(i -> new EarnedCommission(
                LocalDate.of(2026, 11, 1).plusDays(i % 30), new BigDecimal("300.00"), SHARE, new BigDecimal("54.00"),
                new BigDecimal("8.64"), i % 3 == 0 ? "LIQUIDATED" : "ACCRUED", "202611")).toList();
        DistributorStatementAssembler assembler = new DistributorStatementAssembler();
        long start = System.nanoTime();
        DocumentModel last = null;
        for (int i = 0; i < 200; i++) {
            last = assembler.assemble(ctx(v, commission(earned))).model();
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(rows(last, "COLOC")).hasSize(200);
        assertThat(ms).as("200 estados de 200 colocaciones tardaron " + ms + " ms").isLessThan(2_000);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private DocumentModel assemble(SourceView v, DistributorCommission commission) {
        return new DistributorStatementAssembler().assemble(ctx(v, commission)).model();
    }

    private AssemblyContext ctx(SourceView v, DistributorCommission commission) {
        CutoffFacts f = Samples.facts(account, party, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 5,
                v.closing().total().toPlainString(), null, null);
        return new AssemblyContext(UUID.randomUUID(), f, v, Samples.persona(party, "Distribuidora"), Map.of(),
                Optional.empty(), new StatementProperties(), MX, Instant.parse("2026-12-01T06:05:00Z"), commission);
    }

    private static DistributorCommission commission(List<EarnedCommission> earned) {
        return new DistributorCommission(SHARE, earned);
    }

    private static List<Map<String, Object>> rows(DocumentModel m, String section) {
        return m.rows().stream().filter(r -> section.equals(r.get("section")))
                .map(r -> (Map<String, Object>) new LinkedHashMap<String, Object>(r)).toList();
    }

    private static List<UUID> clients(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static InstallmentLine installment(int number, String principal, String interest, String tax) {
        BigDecimal total = new BigDecimal(principal).add(new BigDecimal(interest)).add(new BigDecimal(tax));
        return new InstallmentLine(UUID.randomUUID(), number, Samples.CORTE, new BigDecimal(principal),
                new BigDecimal(interest), new BigDecimal(tax), total, "PENDING");
    }

    private static PlacementLine placement(int total, int paid, List<InstallmentLine> due, BigDecimal cat) {
        return new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10000"),
                Instant.parse("2026-10-01T17:00:00Z"), total, paid, new BigDecimal("1148.00"), new BigDecimal("8000.00"),
                Samples.CORTE, due, 0, null, false, false, null, null, cat);
    }

    /** A view whose obligation is the billed principal plus the period's charges, and optionally arrears. */
    private SourceView view(List<PlacementLine> placements, String principalDue, String charges) {
        return view(placements, principalDue, charges, "0");
    }

    private SourceView view(List<PlacementLine> placements, String principalDue, String charges, String arrears) {
        SourceView base = Samples.distribuidora(account, party, clients(1));
        return new SourceView(base.account(), base.from(), base.to(), base.dueUntil(), base.opening(), base.closing(),
                base.totals(), List.of(), List.of(), placements, null, null, new BigDecimal(principalDue),
                new BigDecimal(charges), new BigDecimal(arrears), List.of());
    }
}
