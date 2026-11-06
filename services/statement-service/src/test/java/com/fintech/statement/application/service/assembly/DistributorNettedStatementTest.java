package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.EarnedCommission;
import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.LadderStep;
import com.fintech.statement.application.port.out.StatementSource.Movement;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.support.Samples;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The distributor's statement when her commission is netted from what she delivers: the ladder of what she delivers
 * by the day she pays, her flat commission per payment on each placement, the two numbers (her share of what her
 * clients pay and what that is of the interest), what she already deducted, and the block notice when she is late.
 */
class DistributorNettedStatementTest {

    private static final ZoneId MX = ZoneId.of("America/Mexico_City");
    private static final BigDecimal SHARE = new BigDecimal("0.18");
    private final UUID account = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    /** Two placements billing 547.98 and 328.62 this statement (interest 171.53 and 61.35), commissions 98.64 and 59.15. */
    private List<PlacementLine> placements() {
        InstallmentLine a = new InstallmentLine(UUID.randomUUID(), 3, Samples.LIMITE, new BigDecimal("349.01"),
                new BigDecimal("171.53"), new BigDecimal("27.44"), new BigDecimal("547.98"), "PENDING");
        InstallmentLine b = new InstallmentLine(UUID.randomUUID(), 5, Samples.LIMITE, new BigDecimal("257.45"),
                new BigDecimal("61.35"), new BigDecimal("9.82"), new BigDecimal("328.62"), "PENDING");
        return List.of(
                new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("5000"), Instant.parse("2026-10-01T17:00:00Z"),
                        12, 2, new BigDecimal("547.98"), new BigDecimal("4316.57"), Samples.CORTE, List.of(a), 0, null, false, false,
                        null, null, new BigDecimal("1.5388"), new BigDecimal("98.64"), new BigDecimal("0.8600"),
                        new BigDecimal("1183.69"), new BigDecimal("1376.60")),
                new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("3000"), Instant.parse("2026-09-01T17:00:00Z"),
                        12, 4, new BigDecimal("328.62"), new BigDecimal("1964.00"), Samples.CORTE, List.of(b), 0, null, false, false,
                        null, null, new BigDecimal("1.5390"), new BigDecimal("59.15"), new BigDecimal("0.8600"),
                        new BigDecimal("709.80"), new BigDecimal("825.90")));
    }

    private static List<LadderStep> ladder() {
        return List.of(new LadderStep(0, new BigDecimal("0.18"), new BigDecimal("157.79")),
                new LadderStep(1, new BigDecimal("0.14"), new BigDecimal("122.73")),
                new LadderStep(2, new BigDecimal("0.11"), new BigDecimal("96.43")),
                new LadderStep(3, new BigDecimal("0.07"), new BigDecimal("61.36")),
                new LadderStep(4, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /** A netted view: the obligation is the billed principal plus the period's charges (876.60 in all). */
    private SourceView nettedView(List<PlacementLine> placements, String arrears) {
        SourceView base = Samples.distribuidora(account, party, List.of(UUID.randomUUID()));
        return new SourceView(base.account(), base.from(), base.to(), base.dueUntil(), base.opening(), base.closing(),
                base.totals(), List.of(), List.of(), placements, null, null, new BigDecimal("606.46"),
                new BigDecimal("270.14"), new BigDecimal(arrears), List.of(), ladder(), "NETTED");
    }

    private AssemblyContext ctx(SourceView v, DistributorCommission commission, int daysDelinquent) {
        CutoffFacts f = Samples.facts(account, party, "DISTRIBUTOR_LINE", "REVOLVING", "DISTRIBUTOR", 5,
                v.closing().total().toPlainString(), null, null);
        CutoffFacts late = new CutoffFacts(f.sourceEventId(), f.runId(), f.creditAccountId(), f.obligorPartyId(), f.productType(),
                f.productBehavior(), f.holderKind(), f.cycleNumber(), f.periodStart(), f.cutoffDate(), f.paymentDueDate(),
                f.balanceAtCutoff(), f.principalAtCutoff(), f.interestAtCutoff(), f.amountDue(), f.minimumPayment(), daysDelinquent);
        return new AssemblyContext(UUID.randomUUID(), late, v, Samples.persona(party, "Distribuidora"), Map.of(),
                Optional.empty(), new StatementProperties(), MX, Instant.parse("2026-12-01T06:05:00Z"), commission);
    }

    private static List<Map<String, Object>> rows(DocumentModel m, String section) {
        return m.rows().stream().filter(r -> section.equals(r.get("section")))
                .map(r -> (Map<String, Object>) new LinkedHashMap<String, Object>(r)).toList();
    }

    @Test
    @DisplayName("arriba: el total, su descuento por pronto pago y la tablita de lo que entrega según el día en que paga")
    void thePaymentTableAtTheTop() {
        SourceView v = nettedView(placements(), "0");
        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(v, new DistributorCommission(SHARE, List.of()), 0)).model();

        // The key figures: the total, her discount if she pays by the due date, and what she delivers then.
        assertThat(m.parameter("BOX1_VALUE")).isEqualTo("$876.60");
        assertThat(m.parameter("BOX2_LABEL")).isEqualTo("Tu descuento por pronto pago (18 %)");
        assertThat(m.parameter("BOX2_VALUE")).isEqualTo("$157.79");
        assertThat(m.parameter("BOX3_LABEL")).asString().startsWith("Entregas a más tardar el ");
        assertThat(m.parameter("BOX3_VALUE")).isEqualTo("$718.81");
        // The table: the due date with the whole discount, each day after with less, the total from the fourth day.
        assertThat(m.parameter("LADDER_TITLE")).isEqualTo("Tu pago con descuento por pronto pago");
        assertThat(m.parameter("LADDER1_WHEN")).asString().startsWith("A más tardar el ").endsWith("(fecha límite)");
        assertThat(m.parameter("LADDER1_RATE")).isEqualTo("18 %");
        assertThat(m.parameter("LADDER1_DISCOUNT")).isEqualTo("$157.79");
        assertThat(m.parameter("LADDER1_DELIVER")).isEqualTo("$718.81");
        assertThat(m.parameter("LADDER2_WHEN")).asString().startsWith("El ");
        assertThat(m.parameter("LADDER2_RATE")).isEqualTo("14 %");
        assertThat(m.parameter("LADDER2_DISCOUNT")).isEqualTo("$122.73");
        assertThat(m.parameter("LADDER2_DELIVER")).isEqualTo("$753.87");
        assertThat(m.parameter("LADDER4_DELIVER")).isEqualTo("$815.24");
        assertThat(m.parameter("LADDER5_WHEN")).asString().startsWith("Del ").endsWith(" en adelante");
        assertThat(m.parameter("LADDER5_RATE")).isEqualTo("Sin descuento");
        assertThat(m.parameter("LADDER5_DISCOUNT")).isEqualTo("—");
        assertThat(m.parameter("LADDER5_DELIVER")).isEqualTo("$876.60");
        assertThat(m.parameter("LADDER_NOTE")).asString().contains("domingos incluidos");
        // The body keeps how the total is formed; the table is not repeated there.
        List<Map<String, Object>> pago = rows(m, "PAGO");
        assertThat(pago.get(0).get("c2")).isEqualTo("$876.60");
        assertThat(pago).noneMatch(r -> String.valueOf(r.get("c1")).startsWith("Entregas"));
        // Each placement's row shows her flat discount on that payment.
        List<Map<String, Object>> coloc = rows(m, "COLOC");
        assertThat(coloc.get(0).get("c8")).isEqualTo("$98.64");
        assertThat(coloc.get(1).get("c8")).isEqualTo("$59.15");
    }

    @Test
    @DisplayName("los dos números: el 18 % de lo que pagan sus clientes, y lo que eso es del interés; lo que se queda Kredius")
    void theTwoNumbers() {
        SourceView v = nettedView(placements(), "0");
        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(v, new DistributorCommission(SHARE, List.of()), 0)).model();

        List<Map<String, Object>> tucom = rows(m, "TUCOM");
        assertThat(tucom.get(0).get("c2")).isEqualTo("$876.60");
        assertThat(tucom.get(0).get("c3")).isEqualTo("18 %");
        assertThat(tucom.get(0).get("c4")).isEqualTo("$157.79");
        // 157.79 of 232.88 of interest: 67.8 %; Kredius keeps 32.2 %, 75.09.
        assertThat(tucom.get(1).get("c2")).isEqualTo("$232.88");
        assertThat(tucom.get(1).get("c3")).isEqualTo("67.76 %");
        assertThat(tucom.get(1).get("c4")).isEqualTo("$157.79");
        assertThat(tucom.get(2).get("c3")).isEqualTo("32.24 %");
        assertThat(tucom.get(2).get("c4")).isEqualTo("$75.09");
        assertThat(m.parameter("PRODUCT_NOTE")).asString().contains("18 %").contains("un día después, 14 %")
                .contains("nunca de su capital").doesNotContain("bloqueada");
    }

    @Test
    @DisplayName("lo que ya descontó de sus entregas en el periodo, con el día y el escalón; y el aviso de bloqueo con atraso")
    void whatSheDeductedAndTheBlockNotice() {
        SourceView v = nettedView(placements(), "812.33");
        LocalDate inPeriod = Samples.CORTE.minusDays(5);
        List<EarnedCommission> earned = List.of(
                new EarnedCommission(inPeriod, new BigDecimal("812.33"), new BigDecimal("0.14"), new BigDecimal("113.73"),
                        BigDecimal.ZERO, "COMPENSATED", "202611", "NETTED", 4, inPeriod.minusDays(1), 1),
                new EarnedCommission(Samples.CORTE.minusDays(40), new BigDecimal("700.00"), SHARE, new BigDecimal("126.00"),
                        BigDecimal.ZERO, "COMPENSATED", "202610", "NETTED", 3, null, 0));
        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(v, new DistributorCommission(SHARE, earned), 3)).model();

        List<Map<String, Object>> tucom = rows(m, "TUCOM");
        Map<String, Object> deducted = tucom.stream().filter(r -> String.valueOf(r.get("c1")).startsWith("Aplicado en tu entrega")).findFirst().orElseThrow();
        assertThat(deducted.get("c1")).asString().contains("un día después de la fecha límite").contains("estado 4");
        assertThat(deducted.get("c3")).isEqualTo("14 %");
        assertThat(deducted.get("c4")).isEqualTo("$113.73");
        Map<String, Object> total = tucom.get(tucom.size() - 1);
        assertThat(total.get("c1")).asString().startsWith("Aplicado en tus entregas");
        assertThat(total.get("c4")).isEqualTo("$113.73");
        assertThat(m.parameter("PRODUCT_NOTE")).asString().startsWith("Tu línea está bloqueada para colocar hasta que pagues lo vencido ($812.33)");
    }

    @Test
    @DisplayName("sin neteo (comisión pagada aparte) el estado sigue como antes: su parte del interés, pagada al mes")
    void aPayoutLineIsUnchanged() {
        SourceView base = Samples.distribuidora(account, party, List.of(UUID.randomUUID()));
        SourceView v = new SourceView(base.account(), base.from(), base.to(), base.dueUntil(), base.opening(), base.closing(),
                base.totals(), List.of(), List.of(), placements(), null, null, new BigDecimal("606.46"),
                new BigDecimal("270.14"), BigDecimal.ZERO, List.of());
        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(v, new DistributorCommission(SHARE, List.of()), 0)).model();
        assertThat(rows(m, "PAGO").stream().noneMatch(r -> String.valueOf(r.get("c1")).startsWith("Entregas"))).isTrue();
        // Without a netted discount the table at the top has one row: the total by the due date; the boxes say when.
        assertThat(m.parameter("LADDER_TITLE")).isEqualTo("Tu pago");
        assertThat(m.parameter("LADDER1_WHEN")).asString().startsWith("A más tardar el ");
        assertThat(m.parameter("LADDER1_RATE")).isEqualTo("—");
        assertThat(m.parameter("LADDER2_WHEN")).isNull();
        assertThat(m.parameter("BOX2_LABEL")).isEqualTo("Fecha límite de pago");
        assertThat(rows(m, "TUCOM").get(0).get("c1")).asString().startsWith("Por los pagos de este estado de cuenta, cuando tus clientes");
        assertThat(m.parameter("PRODUCT_NOTE")).asString().contains("se deposita una vez al mes");
    }

    @Test
    @DisplayName("con una quita en el periodo, la línea que resta de las filas dice que fue condonado, no que ya lo pagó")
    void aQuitaInThePeriodIsNamedOnTheTotal() {
        SourceView base = Samples.distribuidora(account, party, List.of(UUID.randomUUID()));
        Movement quita = new Movement(Instant.parse("2026-11-20T18:00:00Z"), "AGREEMENT_QUITA_PARCIAL",
                new BigDecimal("-300.00"), new BigDecimal("4000.00"));
        // The rows ask 876.60; after the quita the obligation is 300 less (306.46 of principal plus the period's charges).
        SourceView v = new SourceView(base.account(), base.from(), base.to(), base.dueUntil(), base.opening(), base.closing(),
                base.totals(), List.of(quita), List.of(), placements(), null, null, new BigDecimal("306.46"),
                new BigDecimal("270.14"), BigDecimal.ZERO, List.of(), ladder(), "NETTED");
        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(v, new DistributorCommission(SHARE, List.of()), 0)).model();
        List<String> pago = rows(m, "PAGO").stream().map(r -> String.valueOf(r.get("c1"))).toList();
        assertThat(pago).anySatisfy(c1 -> assertThat(c1)
                .startsWith("(−) Lo que condonó el convenio de quita del 20 nov 2026"));
        assertThat(pago).noneMatch(c1 -> c1.startsWith("(−) Lo que ya pagaste"));

        // Without a quita, less than the rows is still what she paid ahead.
        DocumentModel sin = new DistributorStatementAssembler().assemble(ctx(new SourceView(base.account(), base.from(), base.to(),
                base.dueUntil(), base.opening(), base.closing(), base.totals(), List.of(), List.of(), placements(), null, null,
                new BigDecimal("306.46"), new BigDecimal("270.14"), BigDecimal.ZERO, List.of(), ladder(), "NETTED"),
                new DistributorCommission(SHARE, List.of()), 0)).model();
        assertThat(rows(sin, "PAGO").stream().map(r -> String.valueOf(r.get("c1"))))
                .anyMatch(c1 -> c1.startsWith("(−) Lo que ya pagaste de esta quincena antes del corte"));
    }

    @Test
    @DisplayName("la fila lleva el descuento sólo de los pagos de este estado, facturados o aún no; uno de un estado anterior va con «—»")
    void onlyThisStatementsPaymentsCarryTheDiscount() {
        // An earlier statement's installment, unpaid at the cutoff: plan date before the period (31 Oct), moved by billing
        // to that statement's due date (here the cutoff day itself, as Holy Week does).
        InstallmentLine previa = new InstallmentLine(UUID.randomUUID(), 2, Samples.CORTE, new BigDecimal("349.01"),
                new BigDecimal("171.53"), new BigDecimal("27.44"), new BigDecimal("547.98"), "PENDING",
                LocalDate.of(2026, 10, 31), new BigDecimal("98.64"));
        // This statement's, already billed: moved to its due date, plan date kept (the cutoff).
        InstallmentLine facturada = new InstallmentLine(UUID.randomUUID(), 3, Samples.LIMITE, new BigDecimal("349.01"),
                new BigDecimal("171.53"), new BigDecimal("27.44"), new BigDecimal("547.98"), "PENDING", Samples.CORTE,
                new BigDecimal("98.64"));
        // This statement's, not billed yet when the statement was assembled: still on its plan date, the cutoff (dist10).
        InstallmentLine sinFacturar = new InstallmentLine(UUID.randomUUID(), 5, Samples.CORTE, new BigDecimal("257.45"),
                new BigDecimal("61.35"), new BigDecimal("9.82"), new BigDecimal("328.62"), "PENDING", null,
                new BigDecimal("59.15"));
        PlacementLine dos = new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("5000"), Instant.parse("2026-10-01T17:00:00Z"),
                12, 1, new BigDecimal("547.98"), new BigDecimal("4316.57"), Samples.LIMITE, List.of(previa, facturada), 0, null, false, false,
                null, null, new BigDecimal("1.5388"), new BigDecimal("197.28"), new BigDecimal("0.8600"),
                new BigDecimal("1183.69"), new BigDecimal("1376.60"));
        PlacementLine aunNo = new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("3000"), Instant.parse("2026-09-01T17:00:00Z"),
                12, 4, new BigDecimal("328.62"), new BigDecimal("1964.00"), Samples.CORTE, List.of(sinFacturar), 0, null, false, false,
                null, null, new BigDecimal("1.5390"), new BigDecimal("59.15"), new BigDecimal("0.8600"),
                new BigDecimal("709.80"), new BigDecimal("825.90"));
        PlacementLine soloPrevia = new PlacementLine(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("3000"), Instant.parse("2026-09-01T17:00:00Z"),
                12, 4, new BigDecimal("328.62"), new BigDecimal("1964.00"), Samples.LIMITE, List.of(previa), 1, Samples.CORTE, false, false,
                null, null, new BigDecimal("1.5390"), new BigDecimal("98.64"), new BigDecimal("0.8600"),
                new BigDecimal("709.80"), new BigDecimal("825.90"));
        SourceView v = nettedView(List.of(dos, aunNo, soloPrevia), "547.98");
        DocumentModel m = new DistributorStatementAssembler().assemble(ctx(v, new DistributorCommission(SHARE, List.of()), 0)).model();
        List<Map<String, Object>> coloc = rows(m, "COLOC");
        // Both installments of the first row are collected; the discount is only on this statement's own payment.
        assertThat(coloc.get(0).get("c6")).isEqualTo("$1,095.96");
        assertThat(coloc.get(0).get("c8")).isEqualTo("$98.64");
        assertThat(coloc.get(1).get("c8")).isEqualTo("$59.15");
        assertThat(coloc.get(2).get("c8")).isEqualTo("—");
        // The base of the discount: this statement's own payments (547.98 + 328.62), not the whole column.
        assertThat(rows(m, "TUCOM").get(0).get("c2")).isEqualTo("$876.60");
    }

    @Test
    @DisplayName("rendimiento: 200 estados neteados de 50 colocaciones se arman en menos de dos segundos")
    void performance() {
        List<PlacementLine> many = IntStream.range(0, 25).boxed().flatMap(i -> placements().stream()).toList();
        SourceView v = nettedView(many, "0");
        AssemblyContext c = ctx(v, new DistributorCommission(SHARE, List.of()), 0);
        long start = System.nanoTime();
        for (int n = 0; n < 200; n++) {
            new DistributorStatementAssembler().assemble(c);
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).as("200 statements took %d ms", ms).isLessThan(2_000);
    }
}
