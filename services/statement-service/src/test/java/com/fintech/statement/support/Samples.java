package com.fintech.statement.support;

import com.fintech.statement.application.port.out.StatementSource.Account;
import com.fintech.statement.application.port.out.StatementSource.Balances;
import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.LadderStep;
import com.fintech.statement.application.port.out.StatementSource.Movement;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.application.port.out.StatementSource.Totals;
import com.fintech.statement.domain.CutoffFacts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Cuentas de ejemplo de las tres formas, con cifras que cuadran entre sí. */
public final class Samples {

    private Samples() {}

    public static final LocalDate CORTE = LocalDate.of(2026, 11, 30);
    public static final LocalDate DESDE = LocalDate.of(2026, 11, 1);
    public static final LocalDate LIMITE = LocalDate.of(2026, 12, 3);

    public static CutoffFacts facts(UUID account, UUID party, String productType, String behavior, String holderKind,
                                    int cycle, String balance, String amountDue, String minimum) {
        return new CutoffFacts(UUID.randomUUID().toString(), UUID.randomUUID(), account, party, productType, behavior,
                holderKind, cycle, DESDE, CORTE, LIMITE, money(balance), money(balance), BigDecimal.ZERO,
                amountDue == null ? null : money(amountDue), minimum == null ? null : money(minimum), 0);
    }

    public static PartySummary persona(UUID id, String nombre) {
        return new PartySummary(id, nombre, "XAXX010101000");
    }

    /** Préstamo personal a 12 meses: dos pagadas, la tercera vence en el período. */
    public static SourceView plazo(UUID account, UUID party) {
        List<InstallmentLine> plan = new ArrayList<>();
        for (int n = 1; n <= 12; n++) {
            LocalDate vence = LocalDate.of(2026, 8, 30).plusMonths(n);
            String estado = n <= 2 ? "PAID" : "PENDING";
            plan.add(new InstallmentLine(account, n, vence, money("1666.67"), money("410.00"), money("65.60"),
                    money("2142.27"), estado));
        }
        return new SourceView(account(account, party, "CTR-202609-0101", "PERSONAL_LOAN", "INSTALLMENT", null, null),
                DESDE, CORTE, LIMITE, balances("16666.66"), balances("16666.66"),
                totals("0", "2142.27", "410.00", "0", "0", "65.60"),
                List.of(new Movement(Instant.parse("2026-11-03T18:00:00Z"), "PAYMENT_APPLIED", money("-2142.27"), money("16666.66"))),
                plan, List.of());
    }

    /** Línea revolvente: disposiciones, un pago, comisión y el devengo del mes. */
    public static SourceView revolvente(UUID account, UUID party) {
        List<Movement> mov = List.of(
                new Movement(Instant.parse("2026-11-04T17:10:00Z"), "DISPOSITION_SELF_USE", money("3500"), money("11500")),
                new Movement(Instant.parse("2026-11-12T20:00:00Z"), "PAYMENT_APPLIED", money("-2000"), money("9500")),
                new Movement(Instant.parse("2026-11-12T20:00:01Z"), "CHARGE_ADMIN_FEE", money("150"), money("9500")),
                new Movement(Instant.parse("2026-11-20T15:30:00Z"), "DISPOSITION_SELF_USE", money("1200"), money("10700")));
        return new SourceView(account(account, party, "CTR-202607-0042", "REVOLVING_LINE", "REVOLVING",
                        money("30000"), money("18950"), REVOLVING_REFERENCE),
                DESDE, CORTE, LIMITE, balances("8000"), balances("10700"),
                totals("4700", "2000", "320.50", "0", "150", "51.28"), mov, List.of(), List.of());
    }

    /** Línea de distribuidora con cinco colocaciones a clientes distintos. */
    public static SourceView distribuidora(UUID account, UUID party, List<UUID> clientes) {
        List<PlacementLine> colocaciones = new ArrayList<>();
        for (int i = 0; i < clientes.size(); i++) {
            UUID disp = UUID.randomUUID();
            List<InstallmentLine> toca = List.of(new InstallmentLine(disp, 3, LocalDate.of(2026, 11, 30),
                    money("833.33"), money("140.00"), money("22.40"), money("995.73"), "PENDING"));
            colocaciones.add(new PlacementLine(disp, clientes.get(i), money("5000"),
                    Instant.parse("2026-10-01T17:00:00Z").plusSeconds(86_400L * i), 6, 2, money("995.73"),
                    money("3333.34"), LocalDate.of(2026, 11, 30), toca));
        }
        // One more, paid ahead within the period: it goes in "liquidadas en el periodo", not under "vigentes".
        if (!clientes.isEmpty()) {
            colocaciones.add(new PlacementLine(UUID.randomUUID(), clientes.get(0), money("4000"),
                    Instant.parse("2026-09-15T17:00:00Z"), 6, 6, money("796.58"), BigDecimal.ZERO, null, List.of(),
                    0, null, true, false, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 12, 15)));
        }
        return new SourceView(account(account, party, "CTR-202609-0007", "DISTRIBUTOR_LINE", "REVOLVING",
                        money("80000"), money("63333.30")),
                DESDE, CORTE, LIMITE, balances("16000"), balances("16666.70"),
                totals("5000", "4978.65", "700.00", "0", "0", "112.00"), List.of(), List.of(), colocaciones);
    }

    /**
     * A distributor's line with her early-payment discount netted, as the product works today: each client's third
     * fortnight of a $5,000 placement at 12 fortnights (349.01 of principal, 171.53 of interest, 27.44 of IVA), due on
     * this statement's due date, with her flat discount on it (18 % of what the client pays, 98.64), and the ladder
     * the portfolio sets on the five of them: 18 / 14 / 11 / 7 %, nothing from the fourth day.
     */
    public static SourceView distribuidoraNeteada(UUID account, UUID party, List<UUID> clientes) {
        List<PlacementLine> colocaciones = new ArrayList<>();
        for (int i = 0; i < clientes.size(); i++) {
            UUID disp = UUID.randomUUID();
            InstallmentLine cuota = new InstallmentLine(disp, 3, LIMITE, money("349.01"), money("171.53"), money("27.44"),
                    money("547.98"), "PENDING", null, money("98.64"));
            colocaciones.add(new PlacementLine(disp, clientes.get(i), money("5000"),
                    Instant.parse("2026-10-01T17:00:00Z").plusSeconds(86_400L * i), 12, 2, money("547.98"),
                    money("4316.57"), LIMITE, List.of(cuota), 0, null, false, false, null, null,
                    new BigDecimal("1.538800"), money("98.64"), new BigDecimal("0.8600"), money("1183.69"),
                    money("1376.60")));
        }
        BigDecimal descuento = money("98.64").multiply(BigDecimal.valueOf(clientes.size()));
        List<LadderStep> escalera = List.of(
                new LadderStep(0, new BigDecimal("0.18"), descuento),
                new LadderStep(1, new BigDecimal("0.14"), escalon(descuento, "0.14")),
                new LadderStep(2, new BigDecimal("0.11"), escalon(descuento, "0.11")),
                new LadderStep(3, new BigDecimal("0.07"), escalon(descuento, "0.07")),
                new LadderStep(4, BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal n = BigDecimal.valueOf(clientes.size());
        return new SourceView(account(account, party, "CTR-202609-0007", "DISTRIBUTOR_LINE", "REVOLVING",
                        money("80000"), money("58416.65")),
                DESDE, CORTE, LIMITE, balances("21583.35"), balances("21583.35"),
                totals("0", "2739.90", "857.65", "0", "0", "137.20"), List.of(), List.of(), colocaciones,
                null, null, money("349.01").multiply(n), money("198.97").multiply(n), BigDecimal.ZERO, List.of(),
                escalera, "NETTED");
    }

    /** The ladder's step: the on-time discount scaled to the step's rate, as the portfolio's netting does. */
    private static BigDecimal escalon(BigDecimal aTiempo, String tasa) {
        return aTiempo.multiply(new BigDecimal(tasa)).divide(new BigDecimal("0.18"), 2, RoundingMode.HALF_UP);
    }

    /** The payment reference of the revolving sample: consecutive 100042 and its Luhn check digit. */
    public static final long REVOLVING_REFERENCE = 1_000_421L;

    private static Account account(UUID id, UUID party, String contrato, String producto, String comportamiento,
                                   BigDecimal limite, BigDecimal disponible) {
        return account(id, party, contrato, producto, comportamiento, limite, disponible, null);
    }

    private static Account account(UUID id, UUID party, String contrato, String producto, String comportamiento,
                                   BigDecimal limite, BigDecimal disponible, Long paymentReference) {
        return new Account(id, contrato, producto + "-V1", producto, comportamiento, party, "ACTIVE",
                new BigDecimal("0.4800"), new BigDecimal("0.7200"), new BigDecimal("0.16"), limite, disponible,
                money("10700"), money("320.50"), BigDecimal.ZERO, money("51.28"), 12, "FRENCH",
                Instant.parse("2026-09-01T17:00:00Z"), "646180157000000004", 0, null, paymentReference);
    }

    private static Balances balances(String total) {
        return new Balances(money(total), BigDecimal.ZERO, BigDecimal.ZERO, money(total));
    }

    private static Totals totals(String disp, String pagos, String interes, String moratorio, String comisiones, String iva) {
        return new Totals(money(disp), money(pagos), money(interes), money(moratorio), money(comisiones), money(iva),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static BigDecimal money(String v) { return new BigDecimal(v); }
}
