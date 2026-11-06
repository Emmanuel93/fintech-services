package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.fintech.statement.application.service.assembly.Formats.date;
import static com.fintech.statement.application.service.assembly.Formats.money;
import static com.fintech.statement.application.service.assembly.Formats.nz;

/**
 * A loan paid in installments (personal, payroll, micro, group, SME). Besides what every statement carries, an
 * installment loan's must show (CONDUSEF DT-ENR art. 36): the credit amount and the balances, the payment of the period
 * broken down into principal, interest and other charges, the total number of payments and the number of this one,
 * the outstanding balance, the due date, and the cost box.
 *
 * <p>The first box is what the client has to do now. When something is owed up to the due date (this period's
 * installment, or earlier ones unpaid) it is "Pago a realizar". When the period's installment was already paid — a
 * client who pays on the due date, the cutoff day — it used to read "Pago del periodo $0.00" with no word about the
 * next payment; now it is the next installment, its number and its date. A settled loan's statement says so and
 * stands as the certificate that nothing is owed (DT-ENR 19-III, 20).
 */
@Component
public class InstallmentStatementAssembler extends StatementAssembler {

    private static final Map<String, String> STATUS = Map.of(
            "PAID", "Pagada", "PENDING", "Pendiente", "OVERDUE", "Vencida", "PARTIAL", "Pago parcial");

    @Override
    public StatementLayout layout() { return StatementLayout.INSTALLMENT; }

    @Override
    public Assembled assemble(AssemblyContext ctx) {
        CutoffFacts f = ctx.facts();
        SourceView v = ctx.view();
        LocalDate limit = v.dueUntil() != null ? v.dueUntil() : f.paymentDueDate();
        List<InstallmentLine> plan = v.installments() == null ? List.of()
                : v.installments().stream().sorted(Comparator.comparingInt(InstallmentLine::number)).toList();

        // What is owed now: due up to the due date and unpaid, earlier ones included.
        BigDecimal owed = plan.stream()
                .filter(i -> !"PAID".equals(i.status()) && !i.dueDate().isAfter(limit))
                .map(i -> nz(i.total())).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Never more than the account owes at the cutoff (the last installment of a plan can exceed the real debt).
        if (f.balanceAtCutoff() != null) {
            owed = owed.min(f.balanceAtCutoff().max(BigDecimal.ZERO));
        }
        List<InstallmentLine> overdue = plan.stream()
                .filter(i -> !"PAID".equals(i.status()) && i.dueDate().isBefore(f.effectivePeriodStart())).toList();
        long paid = plan.stream().filter(i -> "PAID".equals(i.status())).count();
        InstallmentLine next = plan.stream().filter(i -> !"PAID".equals(i.status())).findFirst().orElse(null);
        // The installments of this period: due within it or up to its due date, paid or not.
        List<InstallmentLine> ofThePeriod = plan.stream()
                .filter(i -> !i.dueDate().isBefore(f.effectivePeriodStart()) && !i.dueDate().isAfter(limit)).toList();
        BigDecimal original = plan.stream().map(i -> nz(i.principal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal principal = f.principalAtCutoff() != null ? f.principalAtCutoff() : v.account().principalBalance();
        boolean settled = next == null || "SETTLED".equals(v.account().status())
                          || (nz(f.balanceAtCutoff()).signum() == 0 && overdue.isEmpty());
        var t = v.totals();

        DocumentModel m = common(ctx);
        if (owed.signum() > 0) {
            m.slot("BOX", 1, "Pago a realizar", money(owed))
             .slot("BOX", 2, "Fecha límite de pago", date(f.paymentDueDate()));
        } else if (!settled) {
            m.slot("BOX", 1, "Tu próximo pago (" + next.number() + " de " + plan.size() + ")", money(next.total()))
             .slot("BOX", 2, "Fecha de tu próximo pago", date(next.dueDate()));
        } else {
            m.slot("BOX", 1, "Saldo por pagar", money(BigDecimal.ZERO))
             .slot("BOX", 2, "Estado del crédito", "Liquidado");
        }
        m.slot("BOX", 3, "Saldo insoluto", money(settled ? BigDecimal.ZERO : principal))
         .slot("INFO", 1, "Monto del crédito", money(original))
         .slot("INFO", 2, "Número total de pagos", String.valueOf(plan.size()))
         .slot("INFO", 3, "Pagos realizados", paid + " de " + plan.size())
         .slot("INFO", 4, "Pagos vencidos", overdue.isEmpty() ? "Ninguno"
                 : overdue.size() + " · desde " + date(overdue.get(0).dueDate()))
         .slot("INFO", 5, "Pago del periodo: capital", money(sum(ofThePeriod, InstallmentLine::principal)))
         .slot("INFO", 6, "Pago del periodo: intereses e IVA",
                 money(sum(ofThePeriod, InstallmentLine::interest).add(sum(ofThePeriod, InstallmentLine::tax))))
         .slot("INFO", 7, "Próximo pago", next == null ? "—" : "No. " + next.number() + " · " + date(next.dueDate())
                 + " · " + money(next.total()))
         .slot("INFO", 8, "Días de atraso", String.valueOf(f.daysDelinquent()))
         .put("SUMMARY_TITLE", "Resumen del periodo")
         .slot("SUM", 1, "Saldo anterior", money(previousBalance(ctx)))
         .slot("SUM", 2, "(+) Disposiciones", plus(t.dispositions()))
         .slot("SUM", 3, "(+) Intereses ordinarios", plus(t.ordinaryInterest()))
         .slot("SUM", 4, "(+) IVA de intereses", plus(t.iva()))
         .slot("SUM", 5, "(+) Comisiones y moratorios", plus(nz(t.fees()).add(nz(t.moratoriumInterest()))))
         .slot("SUM", 6, "(−) Pagos recibidos", minus(t.payments()))
         .slot("SUM", 7, "(−) Reversos, quitas y castigos", minus(nz(t.reversals()).add(nz(t.writeOffs()))))
         .put("SUM_TOTAL_LABEL", "Saldo al corte")
         .put("SUM_TOTAL_VALUE", money(f.balanceAtCutoff()))
         .put("EMPTY_TABLE_MESSAGE", "Este crédito todavía no tiene plan de pagos.")
         .put("PRODUCT_NOTE", settled
                 ? "Tu crédito quedó liquidado. Este estado de cuenta hace constar que no tienes adeudos con nosotros "
                   + "por este contrato."
                 : movedNote(ofThePeriod, next));

        // The plan, with the installments of the period marked.
        for (InstallmentLine i : plan) {
            boolean asked = !"PAID".equals(i.status()) && !i.dueDate().isAfter(limit);
            m.row("section", "PLAN", "emphasis", ofThePeriod.contains(i) ? "1" : "",
                  "c1", asked ? "▶" : "",
                  "c2", String.valueOf(i.number()),
                  "c3", date(i.dueDate()),
                  "c4", money(i.principal()),
                  "c5", money(i.interest()),
                  "c6", money(i.tax()),
                  "c7", money(i.total()),
                  "c8", STATUS.getOrDefault(i.status(), i.status()));
        }
        if (plan.isEmpty()) {
            m.row("section", "PLAN", "emphasis", "EMPTY", "c1", "Este crédito todavía no tiene plan de pagos.");
        }
        movements(m, ctx);
        fees(m, ctx);

        BigDecimal cat = contractedCat(ctx);
        String note = null;
        if (cat == null) {
            cat = CreditCost.catFromRate(v.account().nominalRate(), periodsPerYear(plan));
            note = "sin comisiones";
        }
        costs(m, ctx, cat, note, "Saldo insoluto diario · tasa anual ÷ 360");
        return new Assembled(m, owed, null);
    }

    /**
     * The installment of the period (or the next one) moved from the date the plan set — it fell on a weekend or a bank
     * holiday and is due the next business day, or the client skipped a payment: the statement says so, and that paying
     * on the new date is on time.
     */
    static String movedNote(List<InstallmentLine> ofThePeriod, InstallmentLine next) {
        InstallmentLine moved = ofThePeriod.stream().filter(InstallmentLine::moved).findFirst()
                .orElse(next != null && next.moved() ? next : null);
        if (moved == null) return null;
        return "Tu pago " + moved.number() + " se recorrió del " + date(moved.originalDueDate()) + " al "
               + date(moved.dueDate()) + " (día hábil): pagarlo en esa fecha no genera atraso ni intereses moratorios.";
    }

    /** How many payments a year the plan makes, from the spacing of its due dates (12 monthly, 24 fortnightly…). */
    static int periodsPerYear(List<InstallmentLine> plan) {
        if (plan.size() < 2) return 12;
        long days = ChronoUnit.DAYS.between(plan.get(0).dueDate(), plan.get(plan.size() - 1).dueDate());
        double perPeriod = (double) days / (plan.size() - 1);
        if (perPeriod <= 0) return 12;
        return (int) Math.max(1, Math.round(365.0 / perPeriod));
    }

    private static BigDecimal sum(List<InstallmentLine> lines, Function<InstallmentLine, BigDecimal> f) {
        return lines.stream().map(f).map(Formats::nz).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
