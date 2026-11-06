package com.fintech.statement.application.service.assembly;

import com.fintech.documents.DocumentModel;
import com.fintech.documents.Formats;
import com.fintech.statement.application.port.out.StatementSource;
import com.fintech.statement.application.port.out.StatementSource.DistributorCommission;
import com.fintech.statement.application.port.out.StatementSource.EarnedCommission;
import com.fintech.statement.application.port.out.StatementSource.InstallmentLine;
import com.fintech.statement.application.port.out.StatementSource.Movement;
import com.fintech.statement.application.port.out.StatementSource.PartySummary;
import com.fintech.statement.application.port.out.StatementSource.PlacementLine;
import com.fintech.statement.application.port.out.StatementSource.SourceView;
import com.fintech.statement.domain.CutoffFacts;
import com.fintech.statement.domain.StatementLayout;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

import static com.fintech.documents.Formats.date;
import static com.fintech.documents.Formats.money;
import static com.fintech.documents.Formats.nz;
import static com.fintech.documents.Formats.share;

/**
 * A distributor's line. What the distributor needs to know is how much to collect from each client this fortnight,
 * how much she pays, and what she earns. Besides what every statement carries, it shows per placement the number of
 * the payment this statement bills out of all of them, the payment itself, the outstanding balance (CONDUSEF DT-ENR
 * 36-VI), her commission on it, and the placement's CAT as a closed credit (DT-ENR 12).
 *
 * <p>Two tables make the figures add up in front of her: how the total to pay is formed (her clients' payments, plus
 * moratorium and earlier unpaid charges), and her commission (on this statement's payments, earned in the period, and
 * waiting to be paid out).
 *
 * <p>"Colocaciones vigentes" lists only the placements that still owe at the cutoff. The ones settled in the period
 * go in their own table, "Colocaciones liquidadas en el periodo", once, with the day they were settled and whether
 * they were paid ahead; the following statements no longer list them. An overdue placement shows how many payments it
 * owes and since when.
 */
@Component
public class DistributorStatementAssembler extends StatementAssembler {

    /**
     * A distributor's placements pay by fortnight (from the day after one cutoff to the next): each period's interest
     * is the line's rate ÷ 24, charged at the cutoff; the first one, for the days the money was held.
     */
    private static final int PERIODS_PER_YEAR = 24;

    @Override
    public StatementLayout layout() { return StatementLayout.DISTRIBUTOR; }

    @Override
    public Assembled assemble(AssemblyContext ctx) {
        CutoffFacts f = ctx.facts();
        SourceView v = ctx.view();
        var t = v.totals();
        List<PlacementLine> placements = v.placements() == null ? List.of() : v.placements();
        // The live ones, oldest first; the ones settled in the period in a table of their own.
        List<PlacementLine> live = placements.stream().filter(p -> nz(p.outstandingPrincipal()).signum() > 0).toList();
        List<PlacementLine> settled = placements.stream().filter(PlacementLine::settledInPeriod).toList();

        BigDecimal amountDue = v.plansPrincipalDue() != null
                ? amountDue(v.plansPrincipalDue(), v.periodCharges(), v.arrears(), f.balanceAtCutoff())
                : amountDue(live, f.balanceAtCutoff(), f.principalAtCutoff());
        long clients = live.stream().map(PlacementLine::beneficiaryPartyId).filter(Objects::nonNull).distinct().count();
        int overdue = live.stream().mapToInt(PlacementLine::overdueInstallments).sum();
        BigDecimal rateCat = CreditCost.catFromRate(v.account().nominalRate(), PERIODS_PER_YEAR);
        DistributorCommission commission = ctx.commission();
        BigDecimal commissionRate = commission.rate();

        // With her early-payment discount netted, the boxes are what she reads first: the total, her discount if she
        // pays by the due date, and what she delivers then. Otherwise the due date and what the line has available.
        StatementSource.LadderStep onTimeStep = v.commissionNetted() && v.ladder() != null && !v.ladder().isEmpty()
                && nz(amountDue).signum() > 0 ? v.ladder().get(0) : null;
        BigDecimal onTimeDiscount = onTimeStep == null ? null : nz(onTimeStep.commission()).min(nz(amountDue));
        DocumentModel m = common(ctx)
                .slot("BOX", 1, "Total a pagar en el periodo", money(amountDue))
                .slot("BOX", 2, onTimeStep == null ? "Fecha límite de pago"
                                : "Tu descuento por pronto pago (" + share(onTimeStep.rate()) + ")",
                        onTimeStep == null ? date(f.paymentDueDate()) : money(onTimeDiscount))
                .slot("BOX", 3, onTimeStep == null ? "Disponible en la línea"
                                : "Entregas a más tardar el " + date(f.paymentDueDate()),
                        onTimeStep == null ? money(v.account().availableCredit()) : money(nz(amountDue).subtract(onTimeDiscount)))
                .slot("INFO", 1, "Línea autorizada", money(v.account().creditLimit()))
                .slot("INFO", 2, "Disponible", money(v.account().availableCredit()))
                .slot("INFO", 3, "Colocaciones vigentes", String.valueOf(live.size()))
                .slot("INFO", 4, "Clientes con saldo", String.valueOf(clients))
                .slot("INFO", 5, "Colocaciones liquidadas en el periodo", String.valueOf(settled.size()))
                .slot("INFO", 6, "Pagos vencidos de tus clientes", overdue == 0 ? "Ninguno" : String.valueOf(overdue))
                .slot("INFO", 7, "Capital por pagar de las colocaciones",
                        money(live.stream().map(p -> nz(p.outstandingPrincipal())).reduce(BigDecimal.ZERO, BigDecimal::add)))
                .slot("INFO", 8, "Días de atraso", String.valueOf(f.daysDelinquent()))
                .put("SUMMARY_TITLE", "Resumen de la línea")
                .slot("SUM", 1, "Saldo anterior", money(previousBalance(ctx)))
                .slot("SUM", 2, "(+) Colocado en el periodo", plus(t.dispositions()))
                .slot("SUM", 3, "(+) Intereses ordinarios", plus(t.ordinaryInterest()))
                .slot("SUM", 4, "(+) IVA de intereses", plus(t.iva()))
                .slot("SUM", 5, "(+) Comisiones y moratorios", plus(nz(t.fees()).add(nz(t.moratoriumInterest()))))
                .slot("SUM", 6, "(−) Pagos recibidos", minus(t.payments()))
                .slot("SUM", 7, "(−) Reversos y castigos", minus(nz(t.reversals()).add(nz(t.writeOffs()))))
                .put("SUM_TOTAL_LABEL", "Saldo al corte")
                .put("SUM_TOTAL_VALUE", money(f.balanceAtCutoff()))
                .put("EMPTY_TABLE_MESSAGE", "La línea no tiene colocaciones vigentes.")
                .put("PRODUCT_NOTE", productNote(overdue, commissionRate, v.commissionNetted(), f.daysDelinquent(),
                        v.arrears(), v.ladder(), v.lineClosedOn()));

        BigDecimal clientsPay = BigDecimal.ZERO;
        BigDecimal thisStatementPay = BigDecimal.ZERO;
        BigDecimal interestBilled = BigDecimal.ZERO;
        BigDecimal flatCommission = BigDecimal.ZERO;
        // Netted: her early-payment discount on each payment comes from the plan, and only on the payments this
        // statement bills: a payment of an earlier statement still listed (due the cutoff day after Holy Week moved it,
        // or overdue) earns under its own statement's ladder, so its row shows «—». Paid out: her share of the interest.
        boolean netted = v.commissionNetted();
        List<BigDecimal> interests = live.stream()
                .map(p -> netted ? interestDueNow(p, f.effectivePeriodStart()) : interestDueNow(p)).toList();
        List<BigDecimal> rowCommissions = netted || commissionRate == null ? null : commissionsByRow(interests, commissionRate);
        for (int k = 0; k < live.size(); k++) {
            PlacementLine p = live.get(k);
            BigDecimal payment = paymentDueNow(p);
            clientsPay = clientsPay.add(payment);
            thisStatementPay = thisStatementPay.add(netted ? paymentDueNow(p, f.effectivePeriodStart()) : payment);
            interestBilled = interestBilled.add(interests.get(k));
            BigDecimal rowCommission = netted ? discountOnThisStatement(p, f.effectivePeriodStart())
                    : rowCommissions == null ? null : rowCommissions.get(k);
            if (rowCommission != null) flatCommission = flatCommission.add(rowCommission);
            m.row("section", "COLOC", "emphasis", p.overdueInstallments() > 0 ? "1" : "",
                  "c1", clientName(ctx, p),
                  "c2", date(p.placedAt(), ctx.zone()),
                  "c3", money(p.amount()),
                  "c4", paymentNumber(p),
                  "c5", overdueText(p),
                  "c6", money(payment),
                  "c7", money(p.outstandingPrincipal()),
                  "c8", rowCommission == null ? "—" : money(rowCommission),
                  "c9", catText(p.cat() != null ? p.cat() : rateCat).replace(" Sin IVA", ""));
        }
        if (live.isEmpty()) {
            m.row("section", "COLOC", "emphasis", "EMPTY", "c1", "La línea no tiene colocaciones vigentes.");
        }
        settledRows(m, ctx, settled, p -> clientName(ctx, p), " pagos");
        totalRows(m, clientsPay, amountDue, forgivenOn(ctx));
        paymentTable(m, f.paymentDueDate(), amountDue, v.ladder(), netted);
        if (netted) {
            nettedCommissionRows(m, ctx, commission, v.ladder(), thisStatementPay, interestBilled, flatCommission);
        } else {
            commissionRows(m, ctx, commission, interestBilled);
        }
        movements(m, ctx);
        fees(m, ctx);

        BigDecimal cat = contractedCat(ctx);
        costs(m, ctx, cat != null ? cat : rateCat, cat != null ? null : "por colocación",
                "Interés de cada colocación por quincena, del día siguiente a un corte hasta el corte siguiente · tasa "
                        + "anual ÷ 24, cobrado en el corte; la primera quincena, por los días que se tuvo el dinero");
        return new Assembled(m, amountDue, null);
    }

    /**
     * How the total to pay is formed: what her clients pay (the column «A pagar»), and the moratorium, fees and
     * earlier unpaid charges on top. When she is current, the clients' payments are the whole total. Less than the
     * rows ask: she paid part of them before the cutoff, or collections forgave part of what they ask (a quita in the
     * period); the line says which, since «lo que ya pagaste» on a forgiven debt was wrong (D10, 5 Nov E2E).
     */
    static void totalRows(DocumentModel m, BigDecimal clientsPay, BigDecimal amountDue) {
        totalRows(m, clientsPay, amountDue, null);
    }

    static void totalRows(DocumentModel m, BigDecimal clientsPay, BigDecimal amountDue, String forgivenOn) {
        BigDecimal rest = nz(amountDue).subtract(clientsPay);
        m.row("section", "PAGO", "c1", "Pagos de tus clientes en esta quincena (la suma de la columna «A pagar»)",
              "c2", money(clientsPay));
        if (rest.signum() > 0) {
            m.row("section", "PAGO", "c1", "(+) Moratorios, comisiones y cargos pendientes de cortes anteriores",
                  "c2", money(rest));
        } else if (rest.signum() < 0) {
            m.row("section", "PAGO", "c1", forgivenOn != null
                          ? "(−) Lo que condonó el convenio de quita del " + forgivenOn + " (y lo que pagaste antes del corte)"
                          : "(−) Lo que ya pagaste de esta quincena antes del corte",
                  "c2", money(rest));
        }
        m.row("section", "PAGO", "emphasis", "1", "c1", "Total a pagar en el periodo", "c2", money(amountDue));
    }

    /** The day of the period's quita, in her words, or null when the period had none. */
    static String forgivenOn(AssemblyContext ctx) {
        List<Movement> listed = ctx.view().movements() == null ? List.of() : ctx.view().movements();
        return listed.stream().filter(mv -> "AGREEMENT_QUITA_PARCIAL".equals(mv.type()) && mv.appliedAt() != null)
                .map(mv -> date(mv.appliedAt(), ctx.zone())).findFirst().orElse(null);
    }

    /** Rows of the payment table at the top of the statement (the template's LADDER1..LADDER5). */
    static final int PAYMENT_TABLE_ROWS = 5;

    /**
     * The payment table at the top of the statement: what she delivers by the day she pays. The first row is the due
     * date, with her early-payment discount whole (the legal documents call her commission that); each day after it,
     * less discount; from the first day past the ladder, the whole total. The dates are the due date plus the step's
     * days, a weekend in between included: the day the money lands is what counts. Without a netted discount the
     * table has one row, the total by the due date; with nothing to pay, it says so. Every row's parameters are set,
     * null when the row is not printed.
     */
    static void paymentTable(DocumentModel m, LocalDate dueDate, BigDecimal amountDue,
                             List<StatementSource.LadderStep> ladder, boolean netted) {
        BigDecimal total = nz(amountDue);
        List<String[]> rows = new ArrayList<>();
        boolean withDiscount = netted && ladder != null && !ladder.isEmpty() && dueDate != null && total.signum() > 0;
        if (total.signum() <= 0) {
            rows.add(new String[]{"Nada que pagar en este periodo", "—", "—", money(BigDecimal.ZERO)});
        } else if (!withDiscount) {
            rows.add(new String[]{"A más tardar el " + date(dueDate) + " (fecha límite)", "—", "—", money(total)});
        } else {
            // The due date, the days of the ladder and the first day past it: the table never drops the last row.
            List<StatementSource.LadderStep> steps = ladder.size() <= PAYMENT_TABLE_ROWS ? ladder
                    : Stream.concat(ladder.subList(0, PAYMENT_TABLE_ROWS - 1).stream(),
                            Stream.of(ladder.get(ladder.size() - 1))).toList();
            for (StatementSource.LadderStep step : steps) {
                LocalDate day = dueDate.plusDays(step.daysLate());
                BigDecimal discount = nz(step.commission()).min(total).max(BigDecimal.ZERO);
                boolean none = discount.signum() == 0;
                String when = step.daysLate() == 0 ? "A más tardar el " + date(day) + " (fecha límite)"
                        : none && step == steps.get(steps.size() - 1) ? "Del " + date(day) + " en adelante"
                        : "El " + date(day);
                rows.add(new String[]{when, none ? "Sin descuento" : share(step.rate()), none ? "—" : money(discount),
                        money(total.subtract(discount))});
            }
        }
        m.put("LADDER_TITLE", withDiscount ? "Tu pago con descuento por pronto pago" : "Tu pago");
        m.put("LADDER_NOTE", withDiscount
                ? "Cuenta el día en que entra tu pago, domingos incluidos. Tu descuento sale del interés de tus colocaciones, "
                  + "nunca de su capital; los pagos de estados anteriores se rigen por la fecha límite de su propio estado."
                : null);
        for (int n = 1; n <= PAYMENT_TABLE_ROWS; n++) {
            String[] r = n <= rows.size() ? rows.get(n - 1) : null;
            m.put("LADDER" + n + "_WHEN", r == null ? null : r[0]);
            m.put("LADDER" + n + "_RATE", r == null ? null : r[1]);
            m.put("LADDER" + n + "_DISCOUNT", r == null ? null : r[2]);
            m.put("LADDER" + n + "_DELIVER", r == null ? null : r[3]);
        }
    }

    /**
     * Her commission when it is netted: the two numbers of this statement (her flat share of what her clients pay, and
     * what that is of the interest), what Kredius keeps, and what she already deducted from the deliveries of the
     * period, each with the day she paid and the tier it earned.
     */
    static void nettedCommissionRows(DocumentModel m, AssemblyContext ctx, DistributorCommission commission,
                                     List<StatementSource.LadderStep> ladder, BigDecimal clientsPay,
                                     BigDecimal interestBilled, BigDecimal flatCommission) {
        CutoffFacts f = ctx.facts();
        LocalDate from = f.effectivePeriodStart();
        LocalDate to = f.cutoffDate();
        BigDecimal onTime = ladder != null && !ladder.isEmpty() ? nz(ladder.get(0).commission()) : flatCommission;
        BigDecimal onTimeRate = ladder != null && !ladder.isEmpty() ? ladder.get(0).rate() : commission.rate();
        BigDecimal shareOfInterest = interestBilled.signum() == 0 ? BigDecimal.ZERO
                : onTime.divide(interestBilled, 4, RoundingMode.HALF_UP);
        m.row("section", "TUCOM", "emphasis", "1",
              "c1", "Por los pagos de este estado de cuenta, si los entregas a más tardar el " + date(f.paymentDueDate()),
              "c2", money(clientsPay), "c3", share(onTimeRate), "c4", money(onTime));
        m.row("section", "TUCOM",
              "c1", "Lo mismo, visto sobre el interés de este estado: tu descuento sale del interés, nunca del capital",
              "c2", money(interestBilled), "c3", share(shareOfInterest), "c4", money(onTime));
        m.row("section", "TUCOM",
              "c1", "Lo que se queda Kredius del interés de este estado si pagas a tiempo",
              "c2", money(interestBilled), "c3", share(BigDecimal.ONE.subtract(shareOfInterest).max(BigDecimal.ZERO)),
              "c4", money(interestBilled.subtract(onTime).max(BigDecimal.ZERO)));
        List<EarnedCommission> netted = commission.earned().stream()
                .filter(EarnedCommission::netted)
                .filter(e -> e.valueDate() != null && !e.valueDate().isBefore(from) && !e.valueDate().isAfter(to))
                .toList();
        BigDecimal deducted = BigDecimal.ZERO;
        for (EarnedCommission e : netted) {
            if (nz(e.amount()).signum() < 0) {
                m.row("section", "TUCOM", "c1", "Devuelta: tu pago del " + date(e.valueDate()) + " fue devuelto por el banco",
                      "c2", money(nz(e.basis()).abs()), "c3", share(e.rate()), "c4", money(e.amount()));
                deducted = deducted.add(nz(e.amount()));
                continue;
            }
            int late = e.daysLate() == null ? 0 : e.daysLate();
            String when = late <= 0 ? "a tiempo" : late == 1 ? "un día después de la fecha límite"
                    : late + " días después de la fecha límite";
            m.row("section", "TUCOM",
                  "c1", "Aplicado en tu entrega del " + date(e.valueDate()) + " (" + when
                        + (e.cycleNumber() != null ? ", estado " + e.cycleNumber() : "") + ")",
                  "c2", money(e.basis()), "c3", share(e.rate()), "c4", money(e.amount()));
            deducted = deducted.add(nz(e.amount()));
        }
        m.row("section", "TUCOM", "emphasis", "1",
              "c1", "Aplicado en tus entregas del " + date(from) + " al " + date(to),
              "c2", "", "c3", "", "c4", money(deducted));
    }

    /**
     * Her commission: on this statement's payments (what she earns once her clients pay them), what she earned in
     * the period on the payments received and its IVA, and what is waiting to be paid out.
     */
    static void commissionRows(DocumentModel m, AssemblyContext ctx, DistributorCommission commission,
                               BigDecimal interestBilled) {
        if (commission.rate() == null && commission.earned().isEmpty()) {
            m.row("section", "TUCOM", "emphasis", "EMPTY", "c1", "La línea no tiene una comisión asignada.");
            return;
        }
        CutoffFacts f = ctx.facts();
        LocalDate from = f.effectivePeriodStart();
        LocalDate to = f.cutoffDate();
        List<EarnedCommission> inPeriod = commission.earned().stream()
                .filter(e -> e.valueDate() != null && !e.valueDate().isBefore(from) && !e.valueDate().isAfter(to))
                .filter(e -> !"REVERSED".equals(e.status()))
                .toList();
        BigDecimal basis = inPeriod.stream().map(e -> nz(e.basis())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal earned = inPeriod.stream().map(e -> nz(e.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal iva = inPeriod.stream().map(e -> nz(e.iva())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = commission.earned().stream()
                .filter(e -> "ACCRUED".equals(e.status()) && e.valueDate() != null && !e.valueDate().isAfter(to))
                .map(e -> nz(e.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
        String rate = share(commission.rate());

        m.row("section", "TUCOM",
              "c1", "Por los pagos de este estado de cuenta, cuando tus clientes los cubran",
              "c2", money(interestBilled), "c3", rate,
              "c4", commission.rate() == null ? "—" : money(commissionOn(interestBilled, commission.rate())));
        m.row("section", "TUCOM",
              "c1", "Ganada en el periodo, por los pagos que recibimos del " + date(from) + " al " + date(to),
              "c2", money(basis), "c3", rate, "c4", money(earned));
        m.row("section", "TUCOM", "c1", "IVA de la comisión ganada en el periodo", "c2", "", "c3", "16 %",
              "c4", money(iva));
        m.row("section", "TUCOM", "emphasis", "1", "c1", "Comisión por depositarte al corte", "c2", "", "c3", "",
              "c4", money(pending));
    }

    /** Her share of an interest, to the cent, as commission-service accrues it on each payment. */
    static BigDecimal commissionOn(BigDecimal interest, BigDecimal rate) {
        return nz(interest).multiply(nz(rate)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Her commission on each client's payment, adding up to the cent to the statement's: the rate on all their interest,
     * rounded once ({@link #commissionOn}), spread by each one's interest with the leftover cents going to the largest
     * remainders. Rounding each row on its own made the column add up to a cent more or less than the line «Por los
     * pagos de este estado de cuenta» (D01, 21 March 2025: 101.93 against 101.92).
     */
    static List<BigDecimal> commissionsByRow(List<BigDecimal> interests, BigDecimal rate) {
        int n = interests.size();
        BigDecimal total = commissionOn(interests.stream().map(Formats::nz).reduce(BigDecimal.ZERO, BigDecimal::add), rate);
        long[] cents = new long[n];
        BigDecimal[] remainders = new BigDecimal[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            BigDecimal exact = nz(interests.get(i)).multiply(nz(rate)).movePointRight(2);
            BigDecimal floor = exact.setScale(0, RoundingMode.FLOOR);
            cents[i] = floor.longValueExact();
            remainders[i] = exact.subtract(floor);
            assigned += cents[i];
        }
        long left = total.movePointRight(2).longValueExact() - assigned;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        // Largest remainder first; on a tie, the earlier row, so the result does not depend on the sort.
        Arrays.sort(order, (a, b) -> {
            int byRemainder = remainders[b].compareTo(remainders[a]);
            return byRemainder != 0 ? byRemainder : Integer.compare(a, b);
        });
        for (int i = 0; i < left && i < n; i++) cents[order[i]]++;
        List<BigDecimal> shares = new ArrayList<>(n);
        for (long c : cents) shares.add(BigDecimal.valueOf(c, 2));
        return shares;
    }

    /**
     * «3 de 12»: the number of the payment this statement bills, out of all of them. It used to print how many were
     * already paid, so a new placement read «0 de 8» on the statement that billed its first payment, and again on the
     * next one while it was unpaid. Nothing billed this fortnight (placed on the cutoff day) reads «—».
     */
    static String paymentNumber(PlacementLine p) {
        List<InstallmentLine> due = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
        return due.stream().mapToInt(InstallmentLine::number).max()
                .stream().mapToObj(n -> n + " de " + p.totalInstallments())
                .findFirst().orElse("—");
    }

    /** The overdue payments and since when: «2 desde 18 ago 2025»; «—» when none. */
    static String overdueText(PlacementLine p) {
        if (p.overdueInstallments() == 0) return "—";
        return p.oldestOverdueDue() == null ? String.valueOf(p.overdueInstallments())
                : p.overdueInstallments() + " desde " + date(p.oldestOverdueDue());
    }

    private static String productNote(int overdue, BigDecimal commissionRate) {
        return productNote(overdue, commissionRate, false, 0, null, null, null);
    }

    /**
     * @param netted         her early-payment discount (her commission) is deducted from what she delivers
     * @param daysDelinquent days late at the cutoff: with one or more the line is blocked for new placements
     * @param arrears        what earlier statements asked for and is still unpaid
     * @param lineClosedOn   the day a quita closed the line to new placements for good; null when it is open
     */
    private static String productNote(int overdue, BigDecimal commissionRate, boolean netted, int daysDelinquent,
                                      BigDecimal arrears, List<StatementSource.LadderStep> ladder, LocalDate lineClosedOn) {
        String bloqueo = lineClosedOn != null
                ? "Tu línea quedó cerrada para colocar por el convenio de quita del " + date(lineClosedOn)
                  + ": liquida lo que resta" + (arrears != null && arrears.signum() > 0 ? " (vencido " + money(arrears) + ")" : "") + ". "
                : daysDelinquent > 0
                ? "Tu línea está bloqueada para colocar hasta que pagues lo vencido"
                  + (arrears != null && arrears.signum() > 0 ? " (" + money(arrears) + ")" : "") + ". "
                : "";
        String atraso = overdue > 0
                ? "Tienes " + overdue + (overdue == 1 ? " pago vencido" : " pagos vencidos")
                  + " de tus clientes: cubrirlo detiene los intereses moratorios y libera tu disponible. "
                : "";
        String comision;
        if (commissionRate == null) {
            comision = "";
        } else if (netted) {
            StringBuilder escalera = new StringBuilder();
            if (ladder != null) {
                for (StatementSource.LadderStep s : ladder) {
                    if (s.daysLate() == 0 || nz(s.commission()).signum() == 0) continue;
                    escalera.append(s.daysLate() == 1 ? "; un día después, " : "; " + s.daysLate() + " días después, ")
                            .append(share(s.rate()));
                }
            }
            comision = "Tu descuento por pronto pago es el " + share(commissionRate) + " de lo que pagan tus clientes si "
                    + "entregas a más tardar la fecha límite" + escalera + "; después ya no hay descuento. Se aplica en lo "
                    + "que entregas, sale del interés de cada colocación y nunca de su capital, y cuenta el día en que "
                    + "entra tu pago, domingos incluidos. Los pagos de estados anteriores que siguen en la tabla se "
                    + "rigen por la fecha límite de su propio estado.";
        } else {
            comision = "Ganas el " + share(commissionRate) + " del interés que pagan tus clientes: se calcula con cada pago que "
                    + "recibimos y se deposita una vez al mes, con su IVA y las retenciones de tu régimen fiscal.";
        }
        String nota = (bloqueo + atraso + comision).trim();
        return nota.isEmpty() ? null : nota;
    }

    /**
     * What the distributor pays this period: the principal of the placements' installments this statement bills,
     * plus what the line accrued up to the cutoff (interest, IVA, moratorium, fees), never more than it owes. Used
     * only when the portfolio's view does not carry the statement's obligation (an older event).
     */
    static BigDecimal amountDue(List<PlacementLine> colocaciones, BigDecimal balanceAtCutoff, BigDecimal principalAtCutoff) {
        BigDecimal aPagar;
        if (principalAtCutoff != null && balanceAtCutoff != null) {
            BigDecimal capital = colocaciones.stream().map(DistributorStatementAssembler::principalDueNow)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal devengado = balanceAtCutoff.subtract(principalAtCutoff).max(BigDecimal.ZERO);
            aPagar = capital.add(devengado);
        } else {
            aPagar = colocaciones.stream().map(DistributorStatementAssembler::paymentDueNow).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return balanceAtCutoff == null ? aPagar : aPagar.min(balanceAtCutoff.max(BigDecimal.ZERO));
    }

    /**
     * The statement's obligation, the same the portfolio bills and measures delinquency against
     * (CycleBillingService): the principal of the installments this statement bills, plus what the period charged
     * (interest, IVA, moratorium, fees), plus what earlier statements asked for and is still unpaid. Paying it on
     * time keeps the line current however interest moves between the cutoff and the payment.
     */
    static BigDecimal amountDue(BigDecimal plansPrincipalDue, BigDecimal periodCharges, BigDecimal arrears,
                                BigDecimal balanceAtCutoff) {
        BigDecimal aPagar = nz(plansPrincipalDue).add(nz(periodCharges).max(BigDecimal.ZERO)).add(nz(arrears));
        return balanceAtCutoff == null ? aPagar : aPagar.min(balanceAtCutoff.max(BigDecimal.ZERO));
    }

    /**
     * What the client pays this fortnight: principal, interest and IVA of the installments this statement bills. It is
     * what the distributor collects from each client; the interest of each one is the plan's, charged at the cutoff.
     */
    static BigDecimal paymentDueNow(PlacementLine p) {
        List<InstallmentLine> cuotas = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
        return cuotas.stream()
                .map(i -> i.total() != null ? i.total() : nz(i.principal()).add(nz(i.interest())).add(nz(i.tax())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The interest of the installments this statement bills: what her commission is a share of. */
    static BigDecimal interestDueNow(PlacementLine p) {
        List<InstallmentLine> cuotas = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
        return cuotas.stream().map(i -> nz(i.interest())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The installments of the row that are this statement's own: their plan date (the date they had before billing
     * moved them to a statement's due date) falls in this statement's period. The portfolio may or may not have billed
     * the cycle when the statement is assembled, so an own installment comes either still on its plan date (this
     * cutoff) or already on this statement's due date with its plan date kept; one of an earlier statement still
     * unpaid at the cutoff always carries a plan date before the period, whatever due date it was moved to (the
     * cutoff day itself when Holy Week moved it there). Reading the due date alone depended on that timing: in a
     * statement assembled before billing, every row lost its discount (dist10).
     */
    static List<InstallmentLine> thisStatement(PlacementLine p, LocalDate periodStart) {
        List<InstallmentLine> cuotas = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
        if (periodStart == null) return cuotas;
        return cuotas.stream().filter(i -> planDate(i) != null && !planDate(i).isBefore(periodStart)).toList();
    }

    /** The date the plan set for an installment: the one it had before billing (or a holiday) moved it. */
    private static LocalDate planDate(InstallmentLine i) {
        return i.originalDueDate() != null ? i.originalDueDate() : i.dueDate();
    }

    /** What the client pays of this statement's own installments (the row's «A pagar» less an earlier statement's). */
    static BigDecimal paymentDueNow(PlacementLine p, LocalDate periodStart) {
        return thisStatement(p, periodStart).stream()
                .map(i -> i.total() != null ? i.total() : nz(i.principal()).add(nz(i.interest())).add(nz(i.tax())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The interest of this statement's own installments. */
    static BigDecimal interestDueNow(PlacementLine p, LocalDate periodStart) {
        return thisStatement(p, periodStart).stream().map(i -> nz(i.interest())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Her early-payment discount on the row, netted: the plan's commission of this statement's own installments, from
     * each installment when the view carries it; from the row's figure when it does not (an older view) and every
     * installment listed is this statement's. Null, printed «—», when the row bills nothing of this statement.
     */
    static BigDecimal discountOnThisStatement(PlacementLine p, LocalDate periodStart) {
        List<InstallmentLine> own = thisStatement(p, periodStart);
        if (own.isEmpty()) return null;
        if (own.stream().allMatch(i -> i.commission() != null)) {
            return own.stream().map(InstallmentLine::commission).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        List<InstallmentLine> all = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
        return own.size() == all.size() ? nz(p.commissionPerPayment()) : null;
    }

    private static BigDecimal principalDueNow(PlacementLine p) {
        List<InstallmentLine> cuotas = p.dueInPeriod() == null ? List.of() : p.dueInPeriod();
        return cuotas.stream().map(i -> nz(i.principal())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** El nombre del cliente; si party no lo tiene, un identificador corto que la distribuidora pueda citar. */
    private static String clientName(AssemblyContext ctx, PlacementLine p) {
        PartySummary s = p.beneficiaryPartyId() == null ? null : ctx.beneficiaries().get(p.beneficiaryPartyId());
        if (s != null && s.displayName() != null) return s.displayName();
        String id = p.beneficiaryPartyId() != null ? p.beneficiaryPartyId().toString() : p.dispositionId().toString();
        return "Cliente " + id.substring(0, 8).toUpperCase(Locale.ROOT);
    }
}
