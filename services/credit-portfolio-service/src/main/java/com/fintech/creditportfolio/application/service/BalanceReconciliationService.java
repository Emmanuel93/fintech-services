package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.BalanceEventRepository;
import com.fintech.creditportfolio.application.port.out.ChargeRejectionLog;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.domain.CommissionNetting;
import com.fintech.creditportfolio.domain.StatementCoverage;
import com.fintech.creditportfolio.domain.config.DistributorCommission;
import com.fintech.creditportfolio.domain.config.ProductConfigVersion;
import com.fintech.creditportfolio.domain.BalanceEvent;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.PaymentFacts;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The balance engine (★). Charges/Payments/Collections never write portfolio balances —
 * they emit events; this service applies them to the single source of truth and republishes
 * {@code BalanceUpdated}.
 *
 * <p>Every mutation is idempotent (guarded by {@code sourceEventId} in balance_events) and
 * audited (one balance_events row per applied event).
 */
@Service
@Transactional
public class BalanceReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(BalanceReconciliationService.class);

    private final CreditAccountRepository accountRepository;
    private final BalanceEventRepository balanceEventRepository;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final InstallmentRepository installmentRepository;
    private final DispositionRepository dispositionRepository;
    private final ChargeRejectionLog rejections;
    private final PaymentValueDatePolicy valueDates;

    public BalanceReconciliationService(CreditAccountRepository accountRepository,
                                        BalanceEventRepository balanceEventRepository,
                                        CreditPortfolioEventPublisher eventPublisher,
                                        InstallmentRepository installmentRepository,
                                        DispositionRepository dispositionRepository,
                                        ChargeRejectionLog rejections) {
        this(accountRepository, balanceEventRepository, eventPublisher, installmentRepository, dispositionRepository,
                rejections, null);
    }

    public BalanceReconciliationService(CreditAccountRepository accountRepository,
                                        BalanceEventRepository balanceEventRepository,
                                        CreditPortfolioEventPublisher eventPublisher,
                                        InstallmentRepository installmentRepository,
                                        DispositionRepository dispositionRepository,
                                        ChargeRejectionLog rejections,
                                        PaymentValueDatePolicy valueDates) {
        this(accountRepository, balanceEventRepository, eventPublisher, installmentRepository, dispositionRepository,
                rejections, valueDates, null);
    }

    /** The product's configuration: whether it pays a distributor, and how her commission is settled. */
    private final ProductConfigResolver configResolver;

    @Autowired
    public BalanceReconciliationService(CreditAccountRepository accountRepository,
                                        BalanceEventRepository balanceEventRepository,
                                        CreditPortfolioEventPublisher eventPublisher,
                                        InstallmentRepository installmentRepository,
                                        DispositionRepository dispositionRepository,
                                        ChargeRejectionLog rejections,
                                        PaymentValueDatePolicy valueDates,
                                        ProductConfigResolver configResolver) {
        this.configResolver         = configResolver;
        this.valueDates             = valueDates;
        this.accountRepository      = accountRepository;
        this.balanceEventRepository = balanceEventRepository;
        this.eventPublisher         = eventPublisher;
        this.installmentRepository  = installmentRepository;
        this.dispositionRepository  = dispositionRepository;
        this.rejections             = rejections;
    }

    /**
     * charges.charge-applied → interest or penalty bucket per chargeType.
     * Publishes charge-rejected if the account is in a terminal state (post-check).
     */
    public void onChargeApplied(String sourceEventId, UUID accountId, String chargeType, BigDecimal amount) {
        onChargeApplied(sourceEventId, accountId, chargeType, amount, null);
    }

    /**
     * @param effectiveDate el día al que pertenece el cargo; {@code null} = ahora.
     *
     * <p>Se propaga tal cual al {@code balance-updated}: contabilidad deriva de ahí el período, así
     * que un devengo con fecha de junio se asienta en junio aunque se procese hoy.
     */
    public void onChargeApplied(String sourceEventId, UUID accountId, String chargeType,
                                BigDecimal amount, java.time.LocalDate effectiveDate) {
        onChargeApplied(sourceEventId, accountId, chargeType, amount, effectiveDate, null);
    }

    /**
     * @param dayCloseDate el cierre del día que generó el cargo; {@code null} si no salió de uno. Se guarda
     *                     con el asiento (o con el rechazo): es el lote que se cuadra contra cargos.
     */
    public void onChargeApplied(String sourceEventId, UUID accountId, String chargeType,
                                BigDecimal amount, java.time.LocalDate effectiveDate,
                                java.time.LocalDate dayCloseDate) {
        if (sourceEventId != null && (balanceEventRepository.existsBySourceEventId(sourceEventId)
                                      || rejections.exists(sourceEventId))) {
            log.info("Duplicate charge event sourceEventId={} — skipping (idempotent)", sourceEventId);
            return;
        }
        CreditAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(accountId.toString()));

        // A cancelled credit (D63) takes no new charge either: one that arrives late is rejected, and charges reverses it.
        if (account.getStatus() == CreditAccountStatus.WRITTEN_OFF
                || account.getStatus() == CreditAccountStatus.CLOSED
                || account.getStatus() == CreditAccountStatus.CANCELLED) {
            log.warn("Charge rejected: account {} is in terminal state {} — chargeType={} amount={}",
                    accountId, account.getStatus(), chargeType, amount);
            String motivo = "TERMINAL_ACCOUNT: status=" + account.getStatus();
            // Queda escrito: un cargo rechazado también llegó, y cuenta para cuadrar el día. Sin el
            // registro, un día con una cuenta castigada no cuadraría nunca.
            if (sourceEventId != null) {
                rejections.record(sourceEventId, accountId, chargeType, amount,
                        effectiveDate != null ? effectiveDate : com.fintech.shared.time.BusinessClock.current().today(),
                        dayCloseDate, motivo);
            }
            eventPublisher.publishChargeRejected(sourceEventId, accountId, chargeType, amount, motivo);
            return;
        }
        apply(sourceEventId, accountId, "CHARGE_" + chargeType, amount, acc -> {
            if ("ORDINARY_INTEREST".equalsIgnoreCase(chargeType)) {
                acc.applyInterestAccrual(amount);
            } else if ("IVA".equalsIgnoreCase(chargeType)) {
                // El IVA iba a `penaltyBalance` junto con la mora y las comisiones, así que un
                // crédito al corriente mostraba «Penalización» que en realidad eran impuestos.
                acc.applyIvaCharge(amount);
            } else {
                acc.applyPenaltyCharge(amount);   // moratorium + all fees
            }
        }, effectiveDate, dayCloseDate);
    }

    /** charges.charge-reversed (reversal or waiver) → reverse from the original bucket. */
    public void onChargeReversed(String sourceEventId, UUID accountId, String originalChargeType,
                                 BigDecimal amount, boolean waived) {
        String tipo = waived ? "CHARGE_WAIVED" : "CHARGE_REVERSED";
        // A cancelled credit (D63) gives back only what it took. A charge that arrived after the cancellation was
        // rejected, or will be (the account is terminal), and charges' compensation may reverse it before it hears the
        // rejection: applied, that reversal took income the ledger never booked out of accounting.
        if (sourceEventId != null && !balanceEventRepository.existsBySourceEventId(sourceEventId)
                && accountRepository.findById(accountId)
                        .map(a -> a.getStatus() == CreditAccountStatus.CANCELLED).orElse(false)) {
            log.info("Reversa del cargo {} ignorada: la cuenta {} está cancelada y el cargo nunca entró",
                    sourceEventId, accountId);
            return;
        }
        // Charges publishes the reversal with the charge's own id, the id its charge-applied carried: keyed alone,
        // every reversal and waiver was taken for a redelivery of the charge and dropped. The key is the fact.
        apply(keyOf(tipo, sourceEventId), accountId, tipo,
                amount.negate(), account -> account.reverseCharge(originalChargeType, amount), null, null, originalChargeType);
    }

    /** The idempotency key of a derived fact: its type and the id of what it derives from. */
    static String keyOf(String eventType, String sourceEventId) {
        return sourceEventId == null ? null : eventType + ":" + sourceEventId;
    }

    /**
     * payments.payment-applied → reduce in hierarchy; settle if cleared.
     * Rejects (publishes payment-rejected) if amount exceeds totalDebt to prevent overpayment.
     */
    public void onPaymentApplied(String sourceEventId, UUID accountId, BigDecimal amount) {
        onPaymentApplied(sourceEventId, accountId, amount, PaymentFacts.NONE);
    }

    /**
     * A payment, with where its money came from. It is dated the day the money was received (while that day is still
     * open), not the day it was consumed; the facts travel on to accounting in the balance update.
     */
    public void onPaymentApplied(String sourceEventId, UUID accountId, BigDecimal amount, PaymentFacts payment) {
        PaymentFacts facts = payment != null ? payment : PaymentFacts.NONE;
        if (sourceEventId != null && balanceEventRepository.existsBySourceEventId(sourceEventId)) {
            log.info("Duplicate payment event sourceEventId={} — skipping (idempotent)", sourceEventId);
            return;
        }
        CreditAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(accountId.toString()));

        if (account.getStatus() == CreditAccountStatus.WRITTEN_OFF) {
            recordRecovery(sourceEventId, account, amount, facts);
            return;
        }
        if (account.getStatus() == CreditAccountStatus.CANCELLED) {
            // It never lent anything (D63): a payment is not applied to a fee that is being reversed. It is rejected,
            // and payments keeps it as the payer's balance in favor.
            log.warn("Payment rejected: account {} is CANCELLED — amount={}", accountId, amount);
            eventPublisher.publishPaymentRejected(sourceEventId, accountId, amount,
                    "ACCOUNT_CANCELLED: " + account.getCancellationReason());
            return;
        }

        if (amount.compareTo(account.getTotalDebt()) > 0) {
            log.warn("Payment rejected: amount={} exceeds totalDebt={} for accountId={}",
                    amount, account.getTotalDebt(), accountId);
            eventPublisher.publishPaymentRejected(sourceEventId, accountId, amount,
                    "OVERPAYMENT: amount exceeds totalDebt " + account.getTotalDebt());
            return;
        }
        LocalDate valueDate = valueDates != null ? valueDates.valueDate(facts.receivedAt()) : null;

        // A distributor's statement is paid by her cash plus her commission on it, netted at the tier of the day the
        // money landed. The commission goes first, out of the interest, so the ledger shows where it came from; her
        // cash then pays the IVA, the moratorium, the interest left and the capital.
        Optional<DistributorCommission> terms = commissionTerms(account);
        List<CommissionNetting.Grant> grants = terms.map(t -> nettingFor(account, amount,
                valueDate != null ? valueDate : BusinessClock.current().today(), t)).orElse(List.of());
        BigDecimal netted = grants.stream().map(CommissionNetting.Grant::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.add(netted).compareTo(account.getTotalDebt()) > 0) {
            log.warn("Payment rejected: amount={} plus commission={} exceeds totalDebt={} for accountId={}",
                    amount, netted, account.getTotalDebt(), accountId);
            eventPublisher.publishPaymentRejected(sourceEventId, accountId, amount,
                    "OVERPAYMENT: amount plus the commission netted " + netted + " exceeds totalDebt " + account.getTotalDebt());
            return;
        }
        String settlement = terms.map(DistributorCommission::settlement).orElse(null);
        for (CommissionNetting.Grant g : grants) {
            String key = StatementCoverage.COMMISSION_NETTED + ":" + sourceEventId + ":" + g.statement().cycle();
            apply(key, accountId, StatementCoverage.COMMISSION_NETTED, g.amount().negate(),
                    acc -> acc.applyCommissionCompensation(g.amount()), valueDate, null, null, facts,
                    new Extra(settlement, new BalanceUpdatedEvent.CommissionNetting(g.statement().cycle(), g.statement().due(),
                            g.daysLate(), g.rate(), terms.get().rate(), g.basis(), g.amount(), sourceEventId)));
        }
        // A quita was her exit: the line that settles what was left after one closes with this payment.
        boolean leavesAfterQuita = account.isRevolving()
                && StatementCoverage.quitaOn(balanceEventRepository.paymentsOf(accountId)) != null;
        apply(sourceEventId, accountId, "PAYMENT_APPLIED", amount.negate(), acc -> {
            acc.applyPayment(amount);
            acc.settleIfClear();
            if (leavesAfterQuita) acc.closeAfterQuita();
        }, valueDate, null, null, facts, new Extra(settlement, null));
        applyToSchedule(accountId, amount);
    }

    /** The distributor's commission terms of the account's product when it is netted against her statements. */
    private Optional<DistributorCommission> commissionTerms(CreditAccount account) {
        if (configResolver == null || account == null || !account.isRevolving()) return Optional.empty();
        return configResolver.resolveForAccount(account.getProductCode(), account.getProductVersion())
                .map(ProductConfigVersion::getDistributorCommission)
                .filter(DistributorCommission::isNetted);
    }

    /**
     * The statements this payment completes and the commission each one nets: the plans' scheduled commission of the
     * installments the statement billed, less what earlier payments already netted against it, at the tier of the day.
     */
    List<CommissionNetting.Grant> nettingFor(CreditAccount account, BigDecimal cash, LocalDate valueDate,
                                             DistributorCommission terms) {
        UUID id = account.getCreditAccountId();
        List<StatementCoverage.Obligation> obligations = StatementCoverage.obligationsFrom(
                installmentRepository.findByScheduleIdOrdered(id));
        if (obligations.isEmpty()) return List.of();
        List<UUID> planIds = dispositionRepository.findByCreditAccountId(id).stream().map(d -> d.getDispositionId()).toList();
        List<Installment> plans = planIds.isEmpty() ? List.of() : installmentRepository.findByScheduleIds(planIds);
        Map<Integer, BigDecimal> scheduled = new HashMap<>();
        Map<Integer, BigDecimal> basis = new HashMap<>();
        for (Installment i : plans) {
            if (i.getBilledCycle() == null || i.getStatus().isVoid()) continue;
            scheduled.merge(i.getBilledCycle(), nz(i.getScheduledCommission()), BigDecimal::add);
            BigDecimal collected = nz(i.getPrincipalAmount()).add(nz(i.getInterestAmount()))
                    .add(terms.countsTax() ? nz(i.getTaxAmount()) : BigDecimal.ZERO);
            basis.merge(i.getBilledCycle(), collected, BigDecimal::add);
        }
        List<BalanceEvent> ledger = balanceEventRepository.paymentsOf(id);
        Map<Integer, BigDecimal> netted = new HashMap<>();
        for (BalanceEvent e : ledger) {
            Integer cycle = nettedCycleOf(e);
            if (cycle == null) continue;
            BigDecimal signed = StatementCoverage.COMMISSION_NETTED.equals(e.getEventType())
                    ? e.getDeltaAmount().abs() : e.getDeltaAmount().abs().negate();
            netted.merge(cycle, signed, BigDecimal::add);
        }
        return CommissionNetting.plan(obligations, StatementCoverage.paymentsFrom(ledger), cash, valueDate, scheduled,
                basis, netted, terms);
    }

    /** The statement cycle a netting (or its reversal) was granted against: the last part of its key. */
    private static Integer nettedCycleOf(BalanceEvent e) {
        if (e.getSourceEventId() == null) return null;
        if (!StatementCoverage.COMMISSION_NETTED.equals(e.getEventType())
                && !StatementCoverage.COMMISSION_NETTED_REVERSED.equals(e.getEventType())) return null;
        String key = e.getSourceEventId();
        int at = key.lastIndexOf(':');
        if (at < 0) return null;
        try {
            return Integer.valueOf(key.substring(at + 1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** What else a balance update carries: how the distributor's commission is settled, and a netting itself. */
    private record Extra(String commissionSettlement, BalanceUpdatedEvent.CommissionNetting commission) {}

    /**
     * Pone el calendario a la par de los pagos: revolventes por estado de cuenta ({@link #settleStatements}),
     * préstamos a plazo por cuota ({@link #settleInstallments}), lo más viejo primero.
     *
     * <p>Sin esto el pago bajaba el saldo y el plan seguía diciendo "pago 0 de 12": el adeudo y el calendario se
     * iban separando, y el seguimiento del backoffice —y el contador del home— quedaban mintiendo.
     *
     * <p>No falla el pago si el calendario no se puede actualizar: el abono al saldo ya quedó registrado.
     */
    private void applyToSchedule(UUID accountId, BigDecimal amount) {
        try {
            CreditAccount cuenta = accountRepository.findById(accountId).orElse(null);
            if (cuenta != null && cuenta.isRevolving()) {
                settleStatements(cuenta);
                return;
            }
            if (cuenta != null) {
                settleInstallments(cuenta);
            }
        } catch (Exception ex) {
            // El saldo ya se ajustó: que el calendario no se pueda actualizar no
            // debe deshacer un pago que el cliente ya hizo.
            log.error("No se pudo aplicar el pago al calendario accountId={}: {}",
                    accountId, ex.getMessage());
        }
    }

    /**
     * A statement-billed product (card, line, distributor line): payments cover what the statements asked, oldest
     * first. A statement fully covered settles its cycle installment and the plans' installments it billed; one
     * partly covered is PARTIAL; the rest stay PENDING. Plans' installments no statement billed yet are paid only
     * when the whole debt is. Recomputed from all the payments, so a returned payment reopens what it had covered.
     *
     * <p>Allocating each payment to the plans' installments oldest first marked months of a deferred purchase as
     * paid with money meant for the revolving balance: the next statement then asked to prepay the plan to avoid
     * interest and left its month out of the minimum.
     */
    void settleStatements(CreditAccount account) {
        UUID id = account.getCreditAccountId();
        boolean settled = account.getTotalDebt().compareTo(CENTAVO) < 0;
        List<Installment> cycles = installmentRepository.findByScheduleIdOrdered(id).stream()
                .sorted(Comparator.comparing(Installment::getDueDate).thenComparing(Installment::getInstallmentNumber))
                .toList();
        List<UUID> planIds = dispositionRepository.findByCreditAccountId(id).stream().map(d -> d.getDispositionId()).toList();
        List<Installment> plans = planIds.isEmpty() ? List.of() : installmentRepository.findByScheduleIds(planIds);
        Map<Integer, List<Installment>> plansByCycle = plans.stream().filter(i -> i.getBilledCycle() != null)
                .collect(Collectors.groupingBy(Installment::getBilledCycle));

        // Each payment covers only the statements issued before it (StatementCoverage): what it leaves over lowered
        // the balance and does not pay ahead the minimums of the statements to come.
        Map<UUID, BigDecimal> covered = StatementCoverage.cover(StatementCoverage.obligationsFrom(cycles),
                StatementCoverage.paymentsFrom(balanceEventRepository.paymentsOf(id)), null);
        List<Installment> changed = new ArrayList<>();
        for (Installment cycle : cycles) {
            BigDecimal cyclePaid = covered.getOrDefault(cycle.getInstallmentId(), BigDecimal.ZERO);
            InstallmentStatus status = settled || cycle.getTotalAmount().subtract(cyclePaid).compareTo(CENTAVO) < 0
                    ? InstallmentStatus.PAID
                    : cyclePaid.signum() > 0 ? InstallmentStatus.PARTIAL : InstallmentStatus.PENDING;
            if (cycle.settleAs(status)) {
                changed.add(cycle);
            }
            InstallmentStatus planStatus = status == InstallmentStatus.PAID ? InstallmentStatus.PAID : InstallmentStatus.PENDING;
            for (Installment plan : plansByCycle.getOrDefault(cycle.getInstallmentNumber(), List.of())) {
                if (plan.settleAs(planStatus)) {
                    changed.add(plan);
                }
            }
        }
        if (settled) {
            plans.stream().filter(i -> i.settleAs(InstallmentStatus.PAID)).forEach(changed::add);
        }
        if (!changed.isEmpty()) {
            installmentRepository.saveAll(changed);
        }
    }

    /**
     * An installment loan's schedule, settled from all the payments received net of returns, oldest first: a
     * covered installment is PAID, the one the payments reach into is PARTIAL, the rest stay as they were
     * (PENDING, or OVERDUE once the due job marked them). Recomputed from scratch on every payment and return.
     *
     * <p>Allocating each payment to the installments as it came could not undo a returned payment, and it forgot
     * partial payments: an installment paid in two halves stayed PARTIAL forever, since neither half reached its
     * total. Replaced installments (a restructure superseded them) are left out.
     */
    void settleInstallments(CreditAccount account) {
        UUID id = account.getCreditAccountId();
        BigDecimal pagado = Objects.requireNonNullElse(balanceEventRepository.netPaymentsUpTo(id, HASTA_SIEMPRE), BigDecimal.ZERO);
        boolean liquidada = account.getTotalDebt().compareTo(CENTAVO) < 0;
        LocalDate hoy = BusinessClock.current().today();
        var scheduleIds = new ArrayList<UUID>();
        scheduleIds.add(id);
        dispositionRepository.findByCreditAccountId(id).forEach(d -> scheduleIds.add(d.getDispositionId()));
        List<Installment> plan = installmentRepository.findByScheduleIds(scheduleIds).stream()
                .filter(i -> !i.getStatus().isVoid())
                .sorted(Comparator.comparing(Installment::getDueDate).thenComparing(Installment::getInstallmentNumber))
                .toList();
        List<Installment> cambiadas = new ArrayList<>();
        BigDecimal exigido = BigDecimal.ZERO;
        for (Installment cuota : plan) {
            BigDecimal antes = exigido;
            exigido = exigido.add(cuota.getTotalAmount());
            InstallmentStatus estado;
            if (liquidada || exigido.compareTo(pagado.add(CENTAVO)) <= 0) {
                estado = InstallmentStatus.PAID;
            } else if (antes.compareTo(pagado) < 0) {
                estado = InstallmentStatus.PARTIAL;
            } else {
                estado = cuota.getDueDate().isBefore(hoy) ? InstallmentStatus.OVERDUE : InstallmentStatus.PENDING;
                if (cuota.getStatus() == InstallmentStatus.PENDING && estado == InstallmentStatus.OVERDUE) {
                    // Marking it overdue is the due job's call; a payment only settles or reopens.
                    estado = InstallmentStatus.PENDING;
                }
            }
            if (cuota.reconcileTo(estado)) {
                cambiadas.add(cuota);
            }
        }
        if (!cambiadas.isEmpty()) {
            installmentRepository.saveAll(cambiadas);
            log.info("Calendario conciliado con los pagos accountId={} cuotas={} pagado={}", id, cambiadas.size(), pagado);
        }
    }

    private static final LocalDate HASTA_SIEMPRE = LocalDate.of(9999, 12, 31);
    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    /**
     * payments.payment-returned → what the returned payment had covered is owed again, in the same buckets.
     *
     * <p>Two defects lived here (2 Nov, E06: the bank returned the first installment and the portfolio never knew).
     * The return carries the payment order's id, the same id the applied payment carried, so the idempotency
     * guard took it for a redelivery and dropped it: no returned payment was ever applied. And when it was, the
     * whole amount went back as penalty, so principal, interest and IVA stayed paid and the moratorium income was
     * fiction. Now the return has its own key and restores the breakdown of the entry its payment produced.
     * A return of a payment the portfolio never applied changes nothing.
     */
    public void onPaymentReturned(String sourceEventId, UUID accountId, BigDecimal amount) {
        Optional<BalanceEvent> aplicado = sourceEventId == null ? Optional.empty()
                : balanceEventRepository.findBySourceEventId(sourceEventId)
                        .filter(e -> "PAYMENT_APPLIED".equals(e.getEventType()));
        if (aplicado.isEmpty()) {
            log.warn("payment-returned sin pago aplicado en cartera sourceEventId={} accountId={} amount={} — nada que restaurar",
                    sourceEventId, accountId, amount);
            return;
        }
        Restoration r = restorationOf(aplicado.get(), amount);
        apply(keyOf("PAYMENT_RETURNED", sourceEventId), accountId, "PAYMENT_RETURNED", amount,
                account -> account.restorePayment(r.principal(), r.interest(), r.iva(), r.penalty()));
        // The distributor's commission that payment had netted against her statements is owed again too.
        String prefix = StatementCoverage.COMMISSION_NETTED + ":" + sourceEventId + ":";
        for (BalanceEvent netted : balanceEventRepository.paymentsOf(accountId)) {
            if (!StatementCoverage.COMMISSION_NETTED.equals(netted.getEventType()) || netted.getSourceEventId() == null
                    || !netted.getSourceEventId().startsWith(prefix)) continue;
            BigDecimal principal = netted.getPrincipalDelta() == null ? BigDecimal.ZERO : netted.getPrincipalDelta().negate();
            BigDecimal interest = netted.getInterestDelta() == null ? netted.getDeltaAmount().abs().subtract(principal)
                    : netted.getInterestDelta().negate();
            Integer cycle = nettedCycleOf(netted);
            apply(StatementCoverage.COMMISSION_NETTED_REVERSED + ":" + netted.getSourceEventId(), accountId,
                    StatementCoverage.COMMISSION_NETTED_REVERSED, netted.getDeltaAmount().abs(),
                    account -> account.restoreCommissionCompensation(principal, interest), null, null, null, PaymentFacts.NONE,
                    new Extra(null, new BalanceUpdatedEvent.CommissionNetting(cycle == null ? 0 : cycle, null, 0, null, null,
                            null, netted.getDeltaAmount().abs().negate(), sourceEventId)));
        }
        // What the payment had covered on the schedule or on the statements is open again.
        accountRepository.findById(accountId).ifPresent(cuenta -> {
            if (cuenta.isRevolving()) {
                settleStatements(cuenta);
            } else {
                settleInstallments(cuenta);
            }
        });
    }

    /**
     * A payment to a written-off loan is a recovery: the balances stay at zero (the asset left the balance sheet),
     * the fact goes to the ledger with no amount so a redelivery finds it, and balance-updated carries the payment
     * order id so payments confirms the order. Collections detects the recovery and accounting books it (bank against
     * recoveries income). Before, the payment was rejected as an overpayment of a zero debt.
     */
    private void recordRecovery(String sourceEventId, CreditAccount account, BigDecimal amount, PaymentFacts facts) {
        BalanceEvent asiento = BalanceEvent.record(account.getCreditAccountId(), sourceEventId, "RECOVERY_PAYMENT",
                        BigDecimal.ZERO, account)
                .withBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        balanceEventRepository.save(asiento);
        eventPublisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                account.getCreditAccountId(), account.getObligorPartyId(), account.getPrincipalBalance(),
                account.getAccruedInterestBalance(), account.getPenaltyBalance(), account.getAvailableCredit(),
                account.getTotalDebt(), "RECOVERY_PAYMENT", account.getStatus().name(), account.getBalanceVersion(),
                account.getOriginUnitCode(), null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                asiento.getValueDate()).withSourceEventId(sourceEventId).withPayment(facts));
        log.info("Recuperación sobre cartera castigada accountId={} amount={} sourceEventId={}",
                account.getCreditAccountId(), amount, sourceEventId);
    }

    /** What a returned payment puts back in each bucket. */
    record Restoration(BigDecimal principal, BigDecimal interest, BigDecimal iva, BigDecimal penalty) {}

    /**
     * The buckets the applied payment had reduced, taken from its entry (or, for entries written before the
     * breakdown existed, from the balances before and after it). A return of a different amount restores in the
     * reverse order of the payment hierarchy — principal first — never more than the payment covered in each.
     */
    Restoration restorationOf(BalanceEvent aplicado, BigDecimal amount) {
        BigDecimal p, i, v, m;
        if (aplicado.hasBreakdown()) {
            p = aplicado.getPrincipalDelta().negate();
            i = aplicado.getInterestDelta().negate();
            v = aplicado.getIvaDelta().negate();
            m = aplicado.getPenaltyDelta().negate();
        } else {
            BalanceEvent previo = balanceEventRepository.findPrevious(aplicado).orElse(null);
            p = previo == null ? amount : previo.getPrincipalAfter().subtract(aplicado.getPrincipalAfter());
            i = previo == null ? BigDecimal.ZERO : previo.getInterestAfter().subtract(aplicado.getInterestAfter());
            m = previo == null ? BigDecimal.ZERO : previo.getPenaltyAfter().subtract(aplicado.getPenaltyAfter());
            v = aplicado.getDeltaAmount().negate().subtract(p).subtract(i).subtract(m).max(BigDecimal.ZERO);
        }
        BigDecimal resto = amount;
        BigDecimal rp = resto.min(p.max(BigDecimal.ZERO)); resto = resto.subtract(rp);
        BigDecimal ri = resto.min(i.max(BigDecimal.ZERO)); resto = resto.subtract(ri);
        BigDecimal rv = resto.min(v.max(BigDecimal.ZERO)); resto = resto.subtract(rv);
        BigDecimal rm = resto.min(m.max(BigDecimal.ZERO)); resto = resto.subtract(rm);
        // A return larger than what the payment covered (it should not happen) is owed as principal.
        return new Restoration(rp.add(resto), ri, rv, rm);
    }

    /** collections.write-off-executed → all balances to zero, terminal WRITTEN_OFF. */
    public void onWriteOffExecuted(String sourceEventId, UUID accountId) {
        apply(sourceEventId, accountId, "WRITE_OFF", BigDecimal.ZERO, CreditAccount::executeWriteOff);
    }

    // ── Core: idempotent apply + audit + publish ─────────────────────────────

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation) {
        apply(sourceEventId, accountId, eventType, delta, mutation, null);
    }

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation,
                       java.time.LocalDate effectiveDate) {
        apply(sourceEventId, accountId, eventType, delta, mutation, effectiveDate, null);
    }

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation,
                       java.time.LocalDate effectiveDate, java.time.LocalDate dayCloseDate) {
        apply(sourceEventId, accountId, eventType, delta, mutation, effectiveDate, dayCloseDate, null);
    }

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation,
                       java.time.LocalDate effectiveDate, java.time.LocalDate dayCloseDate, String chargeType) {
        apply(sourceEventId, accountId, eventType, delta, mutation, effectiveDate, dayCloseDate, chargeType, null);
    }

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation,
                       java.time.LocalDate effectiveDate, java.time.LocalDate dayCloseDate, String chargeType,
                       PaymentFacts payment) {
        apply(sourceEventId, accountId, eventType, delta, mutation, effectiveDate, dayCloseDate, chargeType, payment, null);
    }

    private void apply(String sourceEventId, UUID accountId, String eventType,
                       BigDecimal delta, Consumer<CreditAccount> mutation,
                       java.time.LocalDate effectiveDate, java.time.LocalDate dayCloseDate, String chargeType,
                       PaymentFacts payment, Extra extra) {

        if (sourceEventId != null && balanceEventRepository.existsBySourceEventId(sourceEventId)) {
            log.info("Duplicate balance event sourceEventId={} — skipping (idempotent)", sourceEventId);
            return;
        }

        // One writer per account at a time. `balance_version` is a plain counter, not an optimistic lock:
        // on 2026-10-31 overlapping accrual attempts read the same balances and the last save won, so 113
        // interest entries reached the ledger while their balance update was lost. The row lock serializes
        // charges, payments and reversals on the same account; the entry and the balances move together.
        // Lock and reload in one step: a plain lock after an earlier read of the same account in this
        // transaction (the overpayment check) would mutate the stale copy JPA already holds. On 2026-05-26
        // of the first clean run a returned payment and an applied payment arrived 44 ms apart: both
        // reached the ledger, the balances kept only the second one.
        CreditAccount account = accountRepository.lockAndLoad(accountId)
                .orElseThrow(() -> new CreditAccountNotFoundException(accountId.toString()));

        // El desglose se mide aquí, entre el antes y el después de la mutación. Es el único punto
        // donde se conoce con certeza: un pago liquida moratorios, luego interés y al final capital,
        // y ese reparto lo decide `applyPayment`. Deducirlo después, restando saldos en otro
        // servicio, funciona sólo mientras los eventos lleguen en orden.
        BigDecimal capAntes = account.getPrincipalBalance();
        BigDecimal intAntes = account.getAccruedInterestBalance();
        BigDecimal morAntes = account.getPenaltyBalance();
        BigDecimal ivaAntes = account.getIvaBalance();

        mutation.accept(account);
        accountRepository.save(account);

        BigDecimal capDelta = account.getPrincipalBalance().subtract(capAntes);
        BigDecimal intDelta = account.getAccruedInterestBalance().subtract(intAntes);
        BigDecimal ivaDelta = account.getIvaBalance().subtract(ivaAntes);
        // El IVA viaja sumado a los moratorios: contabilidad los abona al mismo auxiliar (1203),
        // así que separarlos aquí obligaría a un cuarto campo que el mayor volvería a fundir.
        BigDecimal morDelta = account.getPenaltyBalance().subtract(morAntes).add(ivaDelta);

        // El importe del hecho se **mide**, no se cree.
        //
        // El `delta` que declara el llamador es una conveniencia y puede no corresponder: el
        // quebranto lo pasaba como ZERO literal porque cuánto se castiga no se sabe hasta después
        // de poner los saldos en cero. Con ese cero, el evento viajaba sin importe y contabilidad lo
        // descartaba —«sólo cambió el estatus»—: el crédito desaparecía de cartera y seguía vivo en
        // el mayor, con la cuenta 1201 sobrando exactamente lo castigado.
        BigDecimal medido = capDelta.add(intDelta).add(morDelta);

        // Fecha valor: la del cargo si la trae (el devengo de D se aplica pasada la medianoche), hoy si no.
        BalanceEvent asiento = BalanceEvent.record(accountId, sourceEventId, eventType, medido, account, effectiveDate)
                .inDayClose(dayCloseDate)
                .withBreakdown(capDelta, intDelta, ivaDelta, account.getPenaltyBalance().subtract(morAntes));
        balanceEventRepository.save(asiento);

        eventPublisher.publishBalanceUpdated(new BalanceUpdatedEvent(
                account.getCreditAccountId(),
                account.getObligorPartyId(),
                account.getPrincipalBalance(),
                account.getAccruedInterestBalance(),
                account.getPenaltyBalance(),
                account.getAvailableCredit(),
                account.getTotalDebt(),
                eventType,
                account.getStatus().name(),
                account.getBalanceVersion(),
                account.getOriginUnitCode(),
                // El hecho ocurrió el día del cargo, no el día en que se reconcilió. Contabilidad
                // deriva el período de aquí, así que es lo que le da historia al mayor.
                effectiveDate != null
                        ? effectiveDate.atTime(23, 0).toInstant(java.time.ZoneOffset.UTC)
                        : null,
                // Y el importe del hecho, que aquí se conoce con certeza: es el mismo que se acaba
                // de guardar en `balance_events`. Que viaje evita que contabilidad lo deduzca
                // restando saldos, que es de donde salían importes de seis cifras en un devengo
                // diario cuando dos eventos del mismo crédito se cruzaban.
                medido, capDelta, intDelta, morDelta,
                // La fecha valor viaja con el saldo: closing sella el corte de D con lo que pertenece a D.
                asiento.getValueDate()).withSourceEventId(sourceEventId).withDetail(ivaDelta, chargeType)
                .withPayment(payment)
                .withCommissionSettlement(extra == null ? null : extra.commissionSettlement())
                .withCommission(extra == null ? null : extra.commission()));

        log.info("Balance reconciled accountId={} event={} totalDebt={} status={}",
                accountId, eventType, account.getTotalDebt(), account.getStatus());
    }
}
