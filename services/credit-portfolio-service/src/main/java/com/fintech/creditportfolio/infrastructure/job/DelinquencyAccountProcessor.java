package com.fintech.creditportfolio.infrastructure.job;

import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import com.fintech.creditportfolio.domain.StatementCoverage;
import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Recalcula los días de atraso de una cuenta.
 *
 * <p>El criterio es el mismo para todos —días desde la cuota vencida más antigua que sigue sin
 * pagarse—. Lo único que cambia es <b>dónde viven esas cuotas</b>:
 *
 * <ul>
 *   <li>Un crédito amortizable tiene un calendario, y cuelga de la cuenta.</li>
 *   <li>Una revolvente tiene <b>uno por disposición</b>: la línea no se amortiza, se amortiza cada
 *       colocación. La mora se mide contra el conjunto — la distribuidora cae en mora cuando deja
 *       de cubrir el pago de lo que dispuso, venga de la colocación que venga.</li>
 * </ul>
 *
 * <p>Esto sólo miraba el calendario de la cuenta. Como una revolvente nunca escribe uno ahí, salía
 * invariablemente con cero días de atraso, y ese cero arrastraba a risk (STAGE_1 sin estimación), a
 * cobranza (ningún caso) y al quebranto (inalcanzable). No era que la mora fuera difícil de
 * producir: el cero era una constante, no una medición.
 */
@Component
public class DelinquencyAccountProcessor {

    private final CreditAccountRepository accountRepository;
    private final InstallmentRepository installmentRepository;
    private final DispositionRepository dispositionRepository;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final BalanceEventRepository ledger;

    @Autowired
    public DelinquencyAccountProcessor(CreditAccountRepository accountRepository,
                                 InstallmentRepository installmentRepository,
                                 DispositionRepository dispositionRepository,
                                 CreditPortfolioEventPublisher eventPublisher,
                                 BalanceEventRepository ledger) {
        this.accountRepository     = accountRepository;
        this.installmentRepository = installmentRepository;
        this.dispositionRepository = dispositionRepository;
        this.eventPublisher        = eventPublisher;
        this.ledger                = ledger;
    }

    /** Without the ledger (unit tests of installment products): statement-billed payments count as zero. */
    public DelinquencyAccountProcessor(CreditAccountRepository accountRepository, InstallmentRepository installmentRepository,
                                       DispositionRepository dispositionRepository, CreditPortfolioEventPublisher eventPublisher) {
        this(accountRepository, installmentRepository, dispositionRepository, eventPublisher, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void process(CreditAccount loaded, LocalDate today) {
        // The job read every account when it started; saving that copy now would put back balances that charges
        // or payments changed since. The account is locked and read again before it is touched.
        CreditAccount account = accountRepository.lockAndLoad(loaded.getCreditAccountId()).orElse(loaded);
        if (account.isRevolving()) {
            processStatementBilled(account, today);
            return;
        }
        List<Installment> overdue = installmentRepository.findPendingOverdueByScheduleId(account.getCreditAccountId(), today);

        // Delinquency is measured on principal, not on installment statuses. An installment's total carries the
        // plan's interest while the account accrues the real one day by day, and a payment capped at the debt
        // leaves the last installments PARTIAL forever: a distributor that had paid everything showed 34 days
        // late and was charged moratorium on principal it no longer owed. What is overdue can never exceed the
        // principal still owed beyond what is not due yet; payments cover the oldest installments first, so
        // what remains overdue is the newest of them. Same rule as the day snapshot.
        BigDecimal porVencer = notYetDuePrincipal(account, today);
        BigDecimal capitalVencido = overduePrincipal(overdue)
                .min(account.getPrincipalBalance().subtract(porVencer).max(BigDecimal.ZERO));
        LocalDate masAntigua = oldestStillOverdue(overdue, capitalVencido);
        int days = masAntigua == null ? 0 : (int) ChronoUnit.DAYS.between(masAntigua, today);

        account.updateDelinquency(days);
        accountRepository.save(account);

        eventPublisher.publishDelinquencyStatusUpdated(new DelinquencyStatusUpdatedEvent(
                account.getCreditAccountId(),
                account.getObligorPartyId(),
                account.getContractNumber(),
                days,
                masAntigua == null ? BigDecimal.ZERO : capitalVencido,
                masAntigua));
    }

    /**
     * A statement-billed product (card, line, distributor line) is late when what its statements asked for is not
     * paid by their due dates, not when an installment of a plan is: interest accrues every day and a payment covers
     * it first, so paying exactly what a statement asked can leave a plan a few pesos behind without the holder
     * owing anything late. The statements' obligations are the cycle installments (scheduleId = account); payments
     * cover the oldest first. Same rule as the day snapshot.
     */
    private void processStatementBilled(CreditAccount account, LocalDate today) {
        List<Installment> cycleInstallments = installmentRepository.findByScheduleId(account.getCreditAccountId());
        // Each payment covers only the statements issued before it (StatementCoverage), up to today.
        List<StatementCoverage.Obligation> obligations = StatementCoverage.obligationsFrom(cycleInstallments);
        Map<UUID, BigDecimal> covered = StatementCoverage.cover(obligations,
                ledger == null ? List.of() : StatementCoverage.paymentsFrom(ledger.paymentsOf(account.getCreditAccountId())), today);
        // The principal each statement billed of the plans: the overdue principal is its unpaid share, not the
        // interest, IVA or moratorium the statement carried (the same figure as the day snapshot).
        Map<Integer, BigDecimal> billedPrincipal = new HashMap<>();
        if (dispositionRepository != null) {
            List<UUID> planIds = dispositionRepository.findByCreditAccountId(account.getCreditAccountId()).stream()
                    .map(d -> d.getDispositionId()).toList();
            if (!planIds.isEmpty()) {
                for (Installment i : installmentRepository.findByScheduleIds(planIds)) {
                    if (i.getBilledCycle() != null && !i.getStatus().isVoid()) {
                        billedPrincipal.merge(i.getBilledCycle(), i.getPrincipalAmount(), BigDecimal::add);
                    }
                }
            }
        }
        BigDecimal outstanding = BigDecimal.ZERO;
        LocalDate oldestDue = null;
        for (StatementCoverage.Obligation o : obligations.stream()
                .filter(o -> o.due().isBefore(today))
                .sorted(Comparator.comparing(StatementCoverage.Obligation::due).thenComparingInt(StatementCoverage.Obligation::cycle))
                .toList()) {
            BigDecimal missing = o.amount().subtract(covered.getOrDefault(o.id(), BigDecimal.ZERO));
            if (missing.compareTo(CENTAVO) >= 0) {
                outstanding = outstanding.add(StatementCoverage.overduePrincipal(o, missing, billedPrincipal.get(o.cycle())));
                if (oldestDue == null) oldestDue = o.due();
            }
        }
        BigDecimal overdue = outstanding.min(account.getTotalDebt().max(BigDecimal.ZERO));
        if (overdue.compareTo(CENTAVO) < 0) {
            oldestDue = null;
            overdue = BigDecimal.ZERO;
        }
        int days = oldestDue == null ? 0 : (int) ChronoUnit.DAYS.between(oldestDue, today);
        account.updateDelinquency(days);
        accountRepository.save(account);
        eventPublisher.publishDelinquencyStatusUpdated(new DelinquencyStatusUpdatedEvent(
                account.getCreditAccountId(), account.getObligorPartyId(), account.getContractNumber(),
                days, overdue, oldestDue));
    }

    /** Principal of the unpaid installments that are not overdue today: due today or later, or not billed yet. */
    private BigDecimal notYetDuePrincipal(CreditAccount account, LocalDate today) {
        return installmentRepository.findByScheduleId(account.getCreditAccountId()).stream()
                .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                .filter(i -> !i.getDueDate().isBefore(today))
                .map(Installment::getPrincipalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The oldest overdue installment the remaining overdue principal still reaches, newest first. */
    private static LocalDate oldestStillOverdue(List<Installment> overdue, BigDecimal capitalVencido) {
        if (capitalVencido.compareTo(CENTAVO) < 0) {
            return null;
        }
        BigDecimal acumulado = BigDecimal.ZERO;
        LocalDate masAntigua = null;
        for (Installment i : overdue.stream().sorted(Comparator.comparing(Installment::getDueDate).reversed()).toList()) {
            if (acumulado.compareTo(capitalVencido) >= 0) {
                break;
            }
            acumulado = acumulado.add(i.getPrincipalAmount() == null ? BigDecimal.ZERO : i.getPrincipalAmount());
            masAntigua = i.getDueDate();
        }
        return masAntigua;
    }

    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    /**
     * El <b>capital</b> de las cuotas vencidas. La base sobre la que se cobra el moratorio.
     *
     * <p>Capital y no importe total: cobrar mora sobre el interés de la cuota es interés sobre
     * interés. Y la diferencia no es menor — en una cuota francesa temprana el interés es la mayor
     * parte del importe.
     *
     * <p>Una cuota PARTIAL cuenta <b>entera</b>. El modelo no lleva el abono acumulado por
     * mensualidad —lo dice {@code Installment.applyPayment}— y sin esa columna, descontar «algo»
     * sería inventarlo. Contarla entera sobreestima; descontar a ojo falsea. Se sobreestima, se
     * declara, y se corrige cuando exista la columna.
     */
    private BigDecimal overduePrincipal(List<Installment> overdue) {
        return overdue.stream()
                .map(Installment::getPrincipalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

}
