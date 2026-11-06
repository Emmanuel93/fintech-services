package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.in.StatementViewUseCase;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CollectionAccountProjection;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.DispositionType;
import com.fintech.creditportfolio.domain.DistributorCommissionSchedule;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.StatementCoverage;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.config.SemiMonthlyCalendar;
import com.fintech.creditportfolio.domain.config.BillingCycle;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.shared.time.BusinessClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Arma la vista del estado de cuenta desde el libro de la cuenta.
 *
 * <h2>Qué se lista y qué se suma</h2>
 *
 * El devengo es diario: un mes de una cuenta son ~30 filas de interés y otras ~30 de IVA. Un estado
 * de cuenta que las listara una por una sería ilegible, así que esos conceptos llegan sumados en
 * {@link Totals}. Lo que el cliente reconoce como un hecho —una disposición, un pago, una comisión,
 * un castigo— se lista con su fecha.
 *
 * <h2>El período va por fecha valor</h2>
 *
 * El período es «del 1 al 15» en días de negocio, y cada movimiento cae en el día al que pertenece
 * ({@code value_date}), no en el instante en que se aplicó. Un pago de las 19:00 del 15 en México es
 * del 15 aunque en UTC ya sea el 16; y el devengo del 15, que se aplica a las 00:05 del 16 porque el
 * cierre del día corre pasada la medianoche, también es del 15.
 */
@Service
public class StatementViewService implements StatementViewUseCase {

    /** Lo que se devenga a diario y llega sumado, no listado. */
    private static final Set<String> DAILY_ACCRUALS =
            Set.of("CHARGE_ORDINARY_INTEREST", "CHARGE_MORATORIUM_INTEREST", "CHARGE_IVA");

    private final FindCreditAccountUseCase accounts;
    private final BalanceEventRepository ledger;
    private final InstallmentRepository installments;
    private final DispositionRepository dispositions;
    private final ProductConfigResolver configResolver;
    private final CollectionAccountProjection collectionAccounts;

    public StatementViewService(FindCreditAccountUseCase accounts, BalanceEventRepository ledger,
                                InstallmentRepository installments, DispositionRepository dispositions,
                                ProductConfigResolver configResolver, CollectionAccountProjection collectionAccounts) {
        this.accounts           = accounts;
        this.ledger             = ledger;
        this.installments       = installments;
        this.dispositions       = dispositions;
        this.configResolver     = configResolver;
        this.collectionAccounts = collectionAccounts;
    }

    @Override
    @Transactional(readOnly = true)
    public StatementView view(UUID creditAccountId, LocalDate from, LocalDate to, LocalDate dueUntil) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("El período termina (" + to + ") antes de empezar (" + from + ")");
        }
        LocalDate hasta = dueUntil == null ? to : dueUntil;
        CreditAccount account = accounts.getById(creditAccountId);

        List<BalanceEvent> periodo = ledger.findBetween(creditAccountId, from, to);
        Balances apertura = ledger.findLastBefore(creditAccountId, from).map(StatementViewService::balances)
                .orElse(new Balances(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        Balances cierre = periodo.isEmpty() ? apertura : balances(periodo.get(periodo.size() - 1));

        List<Movement> movimientos = periodo.stream()
                .filter(e -> !DAILY_ACCRUALS.contains(e.getEventType()))
                .map(e -> new Movement(e.getAppliedAt(), e.getEventType(), e.getDeltaAmount(), e.getPrincipalAfter(), e.getBeneficiaryPartyId()))
                .toList();

        List<InstallmentLine> plan = installments.findByScheduleIdOrdered(creditAccountId).stream()
                .map(StatementViewService::line).toList();

        Optional<ProductConfigVersion> config = configResolver == null ? Optional.empty()
                : configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion());
        // An installment not billed yet is this statement's up to its due date; on a fortnight, never one dated on the
        // next cutoff, as the portfolio bills it (CycleBillingService).
        LocalDate facturableHasta = BillingCycle.lastBillableDay(config.map(ProductConfigVersion::getBillingCycle)
                .orElse(null), to, hasta);

        // The plans of the account (every disposition with its own schedule): what they still owed at the cutoff and
        // what this statement bills of them. The statement's minimum and its "no interest" payment are built on these.
        List<Installment> planes = installments.findByScheduleIds(dispositionsAt(creditAccountId, to)
                .stream().map(Disposition::getDispositionId).toList()).stream()
                .filter(i -> !i.getStatus().isVoid() && !i.paidBy(to))
                .toList();
        BigDecimal enPlanes = planes.stream().map(Installment::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Installment> deEsteEstado = planes.stream()
                .filter(i -> billedByThisStatement(i, to, hasta, facturableHasta)).toList();
        BigDecimal facturadoDePlanes = deEsteEstado.stream().map(Installment::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capitalFacturado = deEsteEstado.stream().map(Installment::getPrincipalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cargos = Objects.requireNonNullElse(ledger.chargesBetween(creditAccountId, from, to), BigDecimal.ZERO);
        // What earlier statements asked for (their cycle installments, due by the cutoff) and was not paid by then.
        // A payment covers only the statements issued before it: paying one statement in full does not pay ahead
        // the next one's minimum (E34: the third statement showed no arrears with the second's minimum unpaid).
        BigDecimal atraso = BigDecimal.ZERO;
        if (account.isRevolving()) {
            List<StatementCoverage.Obligation> asked = StatementCoverage.obligationsFrom(
                    installments.findByScheduleIdOrdered(creditAccountId).stream()
                            .filter(i -> !i.getDueDate().isAfter(to)).toList());
            Map<UUID, BigDecimal> covered = StatementCoverage.cover(asked,
                    StatementCoverage.paymentsFrom(ledger.paymentsOf(creditAccountId)), to);
            atraso = asked.stream()
                    .map(o -> o.amount().subtract(covered.getOrDefault(o.id(), BigDecimal.ZERO)).max(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        // The plans' CAT counts the year in the product's periods (24 fortnights for a distributor's placements), and a
        // placement's first period by the fortnight's cutoff days.
        PlanTerms terms = new PlanTerms(
                PlanCost.periodsPerYear(config.map(ProductConfigVersion::getPaymentFrequency).orElse(null)),
                "SEMI_MONTHLY".equalsIgnoreCase(config.map(ProductConfigVersion::getPaymentFrequency).orElse(null))
                        ? BillingCycle.semiMonthlyOf(config.map(ProductConfigVersion::getBillingCycle).orElse(null))
                        : null,
                config.map(ProductConfigVersion::getDistributorCommission).orElse(null));
        // The distributor's ladder on this statement: her commission on the installments it bills, by the day she pays.
        List<LadderStep> ladder = ladder(terms.commission(), deEsteEstado);
        // A quita closed the line to new placements (her exit): the statement says so instead of "pay what is overdue".
        LocalDate lineClosedOn = terms.commission() == null ? null
                : StatementCoverage.quitaOn(ledger.paymentsOf(creditAccountId));
        return new StatementView(account(account, collectionClabeOf(account)), from, to, hasta, apertura, cierre, totals(periodo),
                movimientos, plan, plans(creditAccountId, DispositionType.THIRD_PARTY_CREDIT, from, to, hasta,
                        facturableHasta, terms),
                enPlanes, facturadoDePlanes, capitalFacturado, cargos, atraso,
                plans(creditAccountId, DispositionType.SELF_USE, from, to, hasta, facturableHasta, terms),
                ladder, terms.commission() == null ? null : terms.commission().settlement(), lineClosedOn);
    }

    /**
     * What the distributor keeps of what her clients pay this statement, step by step: whole on time, less each day
     * after the due date, nothing from the first day past the ladder. The on-time commission is the plans' scheduled
     * one; each step scales it as the netting does.
     */
    static List<LadderStep> ladder(DistributorCommission terms, List<Installment> billed) {
        if (terms == null) return List.of();
        BigDecimal onTime = billed.stream().map(Installment::getScheduledCommission).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LadderStep> steps = new ArrayList<>();
        for (int day = 0; day <= terms.lastTierDay() + 1; day++) {
            steps.add(new LadderStep(day, terms.rateFor(day), terms.at(onTime, day)));
        }
        return steps;
    }

    /** How the plans' CAT is counted: periods a year and, on the fortnight, its cutoff days. */
    private record PlanTerms(int periodsPerYear, SemiMonthlyCalendar fortnight, DistributorCommission commission) {

        PlanTerms(int periodsPerYear, SemiMonthlyCalendar fortnight) {
            this(periodsPerYear, fortnight, null);
        }

        /** The CAT of a placement's plan, its first period as long as the days from the placement to the first cutoff. */
        BigDecimal catOf(Disposition d, List<Installment> schedule) {
            BigDecimal firstPeriod = BigDecimal.ONE;
            if (fortnight != null && d.getCreatedAt() != null && !schedule.isEmpty()) {
                Installment first = schedule.get(0);
                LocalDate firstDue = first.getOriginalDueDate() != null ? first.getOriginalDueDate() : first.getDueDate();
                LocalDate placedOn = d.getCreatedAt().atZone(BusinessClock.current().zone()).toLocalDate();
                firstPeriod = AmortizationEngine.firstFortnightShare(placedOn, firstDue, fortnight);
            }
            return PlanCost.cat(d.getAmount(), schedule, periodsPerYear, firstPeriod);
        }
    }

    /**
     * Whether an unpaid installment is part of what this statement asks for.
     *
     * <p>An installment billed by an earlier statement and still within that statement's payment window
     * (its due date, moved to that statement's, is after this cutoff) belongs to the earlier statement:
     * asking for it again made a distributor that paid each statement pay installments twice. Arrears (due
     * on or before the cutoff and unpaid) and installments due up to this statement's due date are asked for.
     */
    static boolean dueInThisStatement(Installment i, LocalDate cutoff, LocalDate dueUntil) {
        return dueInThisStatement(i, cutoff, dueUntil, dueUntil);
    }

    /**
     * {@link #dueInThisStatement(Installment, LocalDate, LocalDate)} with the last day an installment not billed yet
     * may be dated on to be this statement's ({@code billUntil}): on a fortnight, the day before the next cutoff when
     * holidays moved the due date onto it. Billed ones carry the due date of the statement that billed them.
     */
    static boolean dueInThisStatement(Installment i, LocalDate cutoff, LocalDate dueUntil, LocalDate billUntil) {
        if (!i.getDueDate().isAfter(cutoff)) {
            return true;
        }
        // Due after the cutoff: this statement's if it billed it (its due date was moved to this one's); an earlier
        // one's carries that statement's due date, which is before this one's.
        if (i.getBilledCycle() != null) {
            return i.getDueDate().equals(dueUntil);
        }
        return !i.getDueDate().isAfter(billUntil);
    }

    /**
     * Whether this statement is the one that bills an installment: billed by it (its due date was moved to this
     * statement's), or not billed yet and dated up to {@code billUntil} (the portfolio may not have billed the cycle
     * yet when the statement is assembled): the due date, or on a fortnight the day before the next cutoff when
     * holidays moved the due date onto it. What earlier statements billed is not billed again; if it is unpaid it is
     * in the arrears. One paid by the cutoff is not billed; one paid after it still was.
     */
    static boolean billedByThisStatement(Installment i, LocalDate cutoff, LocalDate dueUntil, LocalDate billUntil) {
        if (i.paidBy(cutoff)) {
            return false;
        }
        if (i.getBilledCycle() != null) {
            return i.getDueDate().equals(dueUntil);
        }
        return !i.getDueDate().isAfter(billUntil);
    }

    /**
     * The account's dispositions of one type that carry their own plan: a distributor's placements to third parties,
     * or a card's purchases in installments. Each one with how it goes — installments paid, overdue and since when,
     * what this statement bills of it — so the statement can show the plan's progress (DT-ENR 36-VI, 37).
     *
     * <p>The plan is read as it stood at the cutoff: an installment paid after it was still owed then, and a
     * disposition made after it belongs to the next statement. A statement assembled the day after its cutoff, or
     * regenerated months later, says the same.
     *
     * <p>Settled ones are left out, except the ones settled in this period, reported once: a statement that listed
     * every placement the distributor ever made buried the live ones under rows of "6/6 · $0.00". Settled in the
     * period means on the day it was fully paid. Judging it by its last due date kept a placement paid ahead in
     * April reported as "settled in the period" in every statement up to that date (DIST0005, cutoffs 3 to 15).
     */
    private List<PlacementLine> plans(UUID creditAccountId, DispositionType type, LocalDate from, LocalDate cutoff,
                                      LocalDate dueUntil, LocalDate billUntil, PlanTerms terms) {
        List<Disposition> conPlan = dispositionsAt(creditAccountId, cutoff).stream()
                .filter(d -> d.getDispositionType() == type)
                .sorted(Comparator.comparing(Disposition::getCreatedAt))
                .toList();
        if (conPlan.isEmpty()) return List.of();

        // One query for every schedule, not one per disposition.
        Map<UUID, List<Installment>> bySchedule = installments.findByScheduleIds(
                        conPlan.stream().map(Disposition::getDispositionId).toList()).stream()
                .collect(Collectors.groupingBy(Installment::getScheduleId));

        List<PlacementLine> lines = new ArrayList<>();
        for (Disposition d : conPlan) {
            List<Installment> schedule = bySchedule.getOrDefault(d.getDispositionId(), List.of()).stream()
                    .filter(i -> !i.getStatus().isVoid())
                    .sorted(Comparator.comparingInt(Installment::getInstallmentNumber)).toList();
            if (schedule.isEmpty()) continue;   // a purchase without a plan is just a movement
            List<Installment> unpaid = schedule.stream().filter(i -> !i.paidBy(cutoff)).toList();
            int paid = schedule.size() - unpaid.size();
            BigDecimal outstanding = unpaid.stream().map(Installment::getPrincipalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Due before the cutoff and still unpaid (one due on the cutoff day is not late yet). A billed one carries
            // its statement's due date.
            List<Installment> overdue = unpaid.stream().filter(i -> i.getDueDate().isBefore(cutoff)).toList();
            LocalDate oldestOverdue = overdue.stream().map(Installment::getDueDate).min(Comparator.naturalOrder()).orElse(null);
            LocalDate lastDue = schedule.stream().map(i -> i.getOriginalDueDate() != null ? i.getOriginalDueDate() : i.getDueDate())
                    .max(Comparator.naturalOrder()).orElse(null);
            boolean paidOff = unpaid.isEmpty();
            LocalDate settledOn = paidOff ? schedule.stream().map(Installment::getPaidOn).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null) : null;
            // Paid off within this period. One paid before the payment day was kept has only its plan to go by.
            boolean paidOffInPeriod = paidOff && (settledOn != null
                    ? !settledOn.isBefore(from)
                    : lastDue != null && !lastDue.isBefore(from));
            if (paidOff && !paidOffInPeriod) continue;

            List<InstallmentLine> aPagar = unpaid.stream()
                    .filter(i -> dueInThisStatement(i, cutoff, dueUntil, billUntil))
                    .map(StatementViewService::line).toList();
            LocalDate siguiente = unpaid.stream().map(Installment::getDueDate)
                    .filter(dd -> dd.isAfter(cutoff)).min(Comparator.naturalOrder()).orElse(null);
            boolean sinIntereses = schedule.stream().allMatch(i -> nz(i.getInterestAmount()).signum() == 0);

            // The distributor's two numbers on this placement: her flat commission per payment, and what the plan's
            // whole commission is of its whole interest. Null on a product that pays no distributor.
            boolean conComision = terms.commission() != null
                    && schedule.stream().anyMatch(i -> i.getScheduledCommission() != null);
            BigDecimal porPago = !conComision ? null : (aPagar.isEmpty()
                    ? unpaid.stream().map(Installment::getScheduledCommission).filter(Objects::nonNull).findFirst().orElse(BigDecimal.ZERO)
                    : unpaid.stream().filter(i -> aPagar.stream().anyMatch(l -> l.number() == i.getInstallmentNumber()))
                            .map(Installment::getScheduledCommission).filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));

            lines.add(new PlacementLine(d.getDispositionId(), d.getBeneficiaryPartyId(), d.getAmount(),
                    d.getCreatedAt(), schedule.size(), paid, schedule.get(0).getTotalAmount(), outstanding, siguiente,
                    aPagar, overdue.size(), oldestOverdue, paidOffInPeriod, sinIntereses, settledOn, lastDue,
                    terms.catOf(d, schedule),
                    porPago,
                    !conComision ? null : DistributorCommissionSchedule.shareOfInterest(schedule),
                    !conComision ? null : schedule.stream().map(Installment::getScheduledCommission).filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add),
                    !conComision ? null : schedule.stream().map(i -> nz(i.getInterestAmount())).reduce(BigDecimal.ZERO, BigDecimal::add)));
        }
        return lines;
    }

    /** The account's dispositions made up to the cutoff: one made after it belongs to the next statement. */
    private List<Disposition> dispositionsAt(UUID creditAccountId, LocalDate cutoff) {
        ZoneId zone = BusinessClock.current().zone();
        return dispositions.findByCreditAccountId(creditAccountId).stream()
                .filter(d -> d.getCreatedAt() == null || !d.getCreatedAt().atZone(zone).toLocalDate().isAfter(cutoff))
                .toList();
    }

    private static Totals totals(List<BalanceEvent> periodo) {
        BigDecimal disposiciones = BigDecimal.ZERO, pagos = BigDecimal.ZERO, interes = BigDecimal.ZERO,
                moratorio = BigDecimal.ZERO, comisiones = BigDecimal.ZERO, iva = BigDecimal.ZERO,
                castigos = BigDecimal.ZERO, reversos = BigDecimal.ZERO, neteado = BigDecimal.ZERO;
        for (BalanceEvent e : periodo) {
            BigDecimal monto = e.getDeltaAmount() == null ? BigDecimal.ZERO : e.getDeltaAmount();
            String tipo = e.getEventType();
            if (tipo.startsWith("DISPOSITION_"))                 disposiciones = disposiciones.add(monto);
            else if (tipo.equals("PAYMENT_APPLIED"))             pagos = pagos.add(monto.abs());
            else if (tipo.equals("PAYMENT_RETURNED"))            pagos = pagos.subtract(monto.abs());
            else if (tipo.equals(StatementCoverage.COMMISSION_NETTED))          neteado = neteado.add(monto.abs());
            else if (tipo.equals(StatementCoverage.COMMISSION_NETTED_REVERSED)) neteado = neteado.subtract(monto.abs());
            else if (tipo.equals("CHARGE_ORDINARY_INTEREST"))    interes = interes.add(monto);
            else if (tipo.equals("CHARGE_MORATORIUM_INTEREST"))  moratorio = moratorio.add(monto);
            else if (tipo.equals("CHARGE_IVA"))                  iva = iva.add(monto);
            else if (tipo.equals("WRITE_OFF"))                   castigos = castigos.add(monto.abs());
            else if (tipo.equals("CHARGE_REVERSED") || tipo.equals("CHARGE_WAIVED")) reversos = reversos.add(monto.abs());
            else if (tipo.startsWith("CHARGE_"))                 comisiones = comisiones.add(monto);
        }
        return new Totals(disposiciones, pagos, interes, moratorio, comisiones, iva, castigos, reversos, neteado);
    }

    private static Balances balances(BalanceEvent e) {
        BigDecimal p = nz(e.getPrincipalAfter()), i = nz(e.getInterestAfter()), m = nz(e.getPenaltyAfter());
        return new Balances(p, i, m, p.add(i).add(m));
    }

    private static InstallmentLine line(Installment i) {
        return new InstallmentLine(i.getScheduleId(), i.getInstallmentNumber(), i.getDueDate(), i.getPrincipalAmount(),
                i.getInterestAmount(), i.getTaxAmount(), i.getTotalAmount(), i.getStatus().name(), i.getOriginalDueDate(),
                i.getScheduledCommission());
    }

    /**
     * The holder's active collection CLABE (the SPEI connector's, one per person): what their statement and their
     * clients' receipts tell them to pay to. Never the account's {@code clabeAccount}, which is where its money goes
     * out to: a payment sent there would never reach the line.
     */
    private String collectionClabeOf(CreditAccount a) {
        if (collectionAccounts == null || a.getObligorPartyId() == null) return null;
        return collectionAccounts.activeClabes(List.of(a.getObligorPartyId())).get(a.getObligorPartyId());
    }

    private static Account account(CreditAccount a, String collectionClabe) {
        return new Account(a.getCreditAccountId(), a.getContractNumber(), a.getProductCode(), a.getProductType(),
                a.getProductBehavior(), a.getObligorPartyId(), a.getStatus().name(), a.getNominalRate(),
                a.getMoratoriumRate(), a.getVatRate(), a.getCreditLimit(), a.getAvailableCredit(),
                a.getPrincipalBalance(), a.getAccruedInterestBalance(), a.getPenaltyBalance(), a.getIvaBalance(),
                a.getAssignedTerm(), a.getAmortizationType(), a.getActivatedAt(), collectionClabe,
                a.getDaysDelinquent(), a.getCat());
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
