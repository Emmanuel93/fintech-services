package com.fintech.statement.application.service.assembly;

import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

import static com.fintech.statement.application.service.assembly.Formats.date;
import static com.fintech.statement.application.service.assembly.Formats.money;
import static com.fintech.statement.application.service.assembly.Formats.nz;

/**
 * A credit card or a revolving line (personal or business). Besides what every statement carries, a card's must show
 * (CONDUSEF DT-ENR art. 37; Banxico C13/2011): the line, the balances, the minimum payment, the payment that avoids
 * interest, the minimum plus the installments of purchases at months, the available credit, the purchases in
 * installments with their total of payments, number of payment and outstanding balance, how many months the balance
 * takes paying only the minimum, and the payment that pays it off in twelve months. A consumer revolving line shows
 * the payoff time too (LTOSF 18 Bis 4).
 *
 * <p>A card's minimum has the Banxico floor; the portfolio bills the same amount (CardMinimumPayment in both).
 */
@Component
public class RevolvingStatementAssembler extends StatementAssembler {

    @Override
    public StatementLayout layout() { return StatementLayout.REVOLVING; }

    @Override
    public Assembled assemble(AssemblyContext ctx) {
        CutoffFacts f = ctx.facts();
        SourceView v = ctx.view();
        var t = v.totals();
        String product = v.account().productType() != null ? v.account().productType() : f.productType();
        boolean card = "CREDIT_CARD".equals(product);
        boolean consumer = !"BUSINESS_REVOLVING_LINE".equals(product);
        BigDecimal balance = nz(f.balanceAtCutoff());
        BigDecimal accrued = f.principalAtCutoff() == null ? null : balance.subtract(f.principalAtCutoff());

        BigDecimal revolvingMinimum = revolvingMinimum(f.minimumPayment(), balance, v.plansOutstanding(), accrued,
                card ? v.account().creditLimit() : null, card);
        BigDecimal minimum = revolvingMinimum == null ? null
                : revolvingMinimum.add(nz(v.plansDue())).setScale(2, RoundingMode.HALF_UP);
        // What earlier statements asked for and is still unpaid is due as well (the portfolio counts it late).
        if (minimum != null && nz(v.arrears()).signum() > 0) {
            minimum = minimum.add(v.arrears()).min(balance.max(minimum));
        }
        BigDecimal noInterest = noInterestPayment(f.amountDue() != null ? f.amountDue() : balance,
                v.plansOutstanding(), v.plansDue(), minimum);

        // Payoff projections over the revolving balance (purchases at months are paid in their own months).
        BigDecimal revolving = balance.subtract(nz(v.plansOutstanding())).max(BigDecimal.ZERO);
        BigDecimal share = f.minimumPayment() != null && balance.signum() > 0
                ? f.minimumPayment().divide(balance, MathContext.DECIMAL64) : null;
        CreditCost.Payoff payoff = CreditCost.monthsPayingOnlyTheMinimum(revolving, v.account().nominalRate(), share,
                v.account().creditLimit(), card);
        BigDecimal in12 = CreditCost.paymentToPayOffIn(revolving, v.account().nominalRate(), 12);

        DocumentModel m = common(ctx)
                .slot("BOX", 1, "Pago para no generar intereses", money(noInterest))
                .slot("BOX", 2, nz(v.plansDue()).signum() > 0 ? "Pago mínimo + compras a meses" : "Pago mínimo",
                        minimum != null ? money(minimum) : "No aplica")
                .slot("BOX", 3, "Fecha límite de pago", date(f.paymentDueDate()))
                .slot("INFO", 1, "Límite de crédito", money(v.account().creditLimit()))
                .slot("INFO", 2, "Crédito disponible", money(v.account().availableCredit()))
                .slot("INFO", 3, "Pago mínimo (sin compras a meses)", revolvingMinimum != null ? money(revolvingMinimum) : "No aplica")
                .slot("INFO", 4, "Compras a meses de este periodo", money(v.plansDue()))
                .slot("INFO", 5, "Pagando sólo el mínimo liquidas en", consumer ? payoffText(payoff) : "—")
                .slot("INFO", 6, "Pago fijo para liquidar en 12 meses", consumer ? money(in12) : "—")
                .slot("INFO", 7, "Saldo sujeto a interés", money(revolving.subtract(nz(accrued)).max(BigDecimal.ZERO)))
                .slot("INFO", 8, "Días de atraso", String.valueOf(f.daysDelinquent()))
                .put("SUMMARY_TITLE", "Resumen del periodo")
                .slot("SUM", 1, "Saldo anterior", money(previousBalance(ctx)))
                .slot("SUM", 2, "(+) Disposiciones", plus(t.dispositions()))
                .slot("SUM", 3, "(+) Intereses ordinarios", plus(t.ordinaryInterest()))
                .slot("SUM", 4, "(+) IVA de intereses", plus(t.iva()))
                .slot("SUM", 5, "(+) Comisiones y moratorios", plus(nz(t.fees()).add(nz(t.moratoriumInterest()))))
                .slot("SUM", 6, "(−) Pagos y abonos", minus(t.payments()))
                .slot("SUM", 7, "(−) Reversos y castigos", minus(nz(t.reversals()).add(nz(t.writeOffs()))))
                .put("SUM_TOTAL_LABEL", "Saldo al corte")
                .put("SUM_TOTAL_VALUE", money(balance))
                .put("EMPTY_TABLE_MESSAGE", "Sin movimientos en el periodo.")
                .put("PRODUCT_NOTE", consumer && revolving.signum() > 0 && !payoff.neverPaysOff()
                        ? "Si sólo pagas el mínimo y no haces nuevas compras, liquidarías tu saldo actual en "
                          + payoffText(payoff) + " pagando " + money(payoff.totalPaid()) + " en total. Con un pago fijo de "
                          + money(in12) + " al mes lo liquidas en 12 meses."
                        : null);

        purchases(m, ctx, v.purchasePlans() == null ? List.of() : v.purchasePlans());
        movements(m, ctx);
        fees(m, ctx);

        BigDecimal cat = contractedCat(ctx);
        String note = null;
        if (cat == null) {
            cat = CreditCost.catFromRate(v.account().nominalRate(), 12);
            note = "sin comisiones";
        }
        costs(m, ctx, cat, note, "Saldo diario insoluto · tasa anual ÷ 360");
        return new Assembled(m, noInterest, minimum);
    }

    /**
     * Purchases and dispositions at months still owed at the cutoff: each with its total of payments, the number of
     * this one and what is still owed (DT-ENR 37). The ones paid off in the period go in their own table, once.
     */
    private static void purchases(DocumentModel m, AssemblyContext ctx, List<PlacementLine> plans) {
        List<PlacementLine> live = plans.stream().filter(p -> !p.settledInPeriod()).toList();
        for (PlacementLine p : live) {
            List<InstallmentLine> now = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
            int number = now.isEmpty() ? p.paidInstallments() : now.get(0).number();
            BigDecimal ofThePeriod = now.stream().map(i -> nz(i.total())).reduce(BigDecimal.ZERO, BigDecimal::add);
            m.row("section", "MSI", "emphasis", now.isEmpty() ? "" : "1",
                  "c1", date(p.placedAt(), ctx.zone()),
                  "c2", "Compra a " + kind(p),
                  "c3", money(p.amount()),
                  "c4", Math.max(number, 1) + " de " + p.totalInstallments(),
                  "c5", money(ofThePeriod),
                  "c6", money(p.outstandingPrincipal()),
                  "c7", p.overdueInstallments() == 0 ? "—" : String.valueOf(p.overdueInstallments()));
        }
        if (live.isEmpty()) {
            m.row("section", "MSI", "emphasis", "EMPTY", "c1", "No tienes compras a meses vigentes.");
        }
        settledRows(m, ctx, plans.stream().filter(PlacementLine::settledInPeriod).toList(),
                p -> "Compra a " + kind(p), " meses");
    }

    private static String kind(PlacementLine p) {
        return p.totalInstallments() + (p.interestFree() ? " meses sin intereses" : " meses con intereses");
    }

    private static String payoffText(CreditCost.Payoff p) {
        if (p.neverPaysOff()) return "más de 50 años";
        if (p.months() == 0) return "—";
        return p.months() == 1 ? "1 mes" : p.months() + " meses";
    }

    /**
     * The minimum asked of the revolving balance: closing's (the product's share) capped at what revolves, and for a
     * card never below the Banxico floor. The installments of purchases at months are added on top by the caller.
     */
    static BigDecimal revolvingMinimum(BigDecimal closingMinimum, BigDecimal balanceAtCutoff, BigDecimal plansOutstanding,
                                       BigDecimal accrued, BigDecimal creditLimit, boolean card) {
        if (closingMinimum == null) {
            return null;
        }
        BigDecimal revolving = nz(balanceAtCutoff).subtract(nz(plansOutstanding)).max(BigDecimal.ZERO);
        if (card) {
            return CardMinimumPayment.revolvingMinimum(closingMinimum, revolving, accrued, creditLimit);
        }
        return closingMinimum.min(revolving).setScale(2, RoundingMode.HALF_UP);
    }

    /** The minimum of a line (no Banxico floor): closing's, capped at what revolves, plus the plans it bills. */
    static BigDecimal minimumDue(BigDecimal closingMinimum, BigDecimal balanceAtCutoff, BigDecimal plansOutstanding,
                                 BigDecimal plansDue) {
        BigDecimal revolving = revolvingMinimum(closingMinimum, balanceAtCutoff, plansOutstanding, null, null, false);
        return revolving == null ? null : revolving.add(nz(plansDue)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The payment that leaves no revolving balance to bear interest: the balance minus what the plans owe and
     * this statement does not bill yet (a deferred purchase is paid in its months, not now). Never below the minimum.
     */
    static BigDecimal noInterestPayment(BigDecimal balance, BigDecimal plansOutstanding, BigDecimal plansDue,
                                        BigDecimal minimum) {
        if (plansOutstanding == null) {
            return balance;
        }
        BigDecimal payment = nz(balance).subtract(nz(plansOutstanding)).add(nz(plansDue)).max(BigDecimal.ZERO);
        if (minimum != null) {
            payment = payment.max(minimum);
        }
        return payment.min(nz(balance).max(nz(minimum))).setScale(2, RoundingMode.HALF_UP);
    }
}
