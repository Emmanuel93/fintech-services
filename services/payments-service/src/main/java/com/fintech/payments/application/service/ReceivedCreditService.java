package com.fintech.payments.application.service;

import com.fintech.payments.application.port.in.ApplyReceivedCreditUseCase;
import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.PaymentEventPublisher;
import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.application.port.out.ReceivedCreditRepository;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import com.fintech.payments.domain.PaymentOrder;
import com.fintech.payments.domain.allocation.AllocationLine;
import com.fintech.payments.domain.allocation.AllocationPlan;
import com.fintech.payments.domain.allocation.AllocationStrategy;
import com.fintech.payments.domain.allocation.LoanDebt;
import com.fintech.payments.domain.balance.BalanceMovementType;
import com.fintech.payments.domain.credit.CreditAllocation;
import com.fintech.payments.domain.credit.ReceivedCredit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies a credit received on a payer's CLABE. The strategy decides where each peso goes (referenced loan, seniority,
 * balance in favor); this service records the decision line by line, creates one payment order per loan line with
 * the credit's two dates, and credits the rest to the payer's balance. Every peso of the credit is accounted for.
 *
 * <p>Idempotent by the credit: a redelivered {@code stp.credit-received} applies nothing twice.
 */
@Service
@Transactional
public class ReceivedCreditService implements ApplyReceivedCreditUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReceivedCreditService.class);

    private final ReceivedCreditRepository credits;
    private final AccountBalanceSnapshotRepository snapshots;
    private final PaymentOrderRepository orders;
    private final PaymentEventPublisher events;
    private final CustomerBalanceService balances;
    private final AllocationStrategy strategy;

    public ReceivedCreditService(ReceivedCreditRepository credits, AccountBalanceSnapshotRepository snapshots,
                                 PaymentOrderRepository orders, PaymentEventPublisher events,
                                 CustomerBalanceService balances, AllocationStrategy strategy) {
        this.credits = credits;
        this.snapshots = snapshots;
        this.orders = orders;
        this.events = events;
        this.balances = balances;
        this.strategy = strategy;
    }

    @Override
    public void apply(ReceivedCreditCommand c) {
        if (credits.exists(c.inboundCreditId())) {
            log.info("Abono {} ya aplicado — se ignora la reentrega", c.trackingKey());
            return;
        }
        List<AccountBalanceSnapshot> loans = snapshots.findByObligorPartyId(c.partyId());
        Map<UUID, AccountBalanceSnapshot> byId = loans.stream()
                .collect(Collectors.toMap(AccountBalanceSnapshot::getCreditAccountId, Function.identity()));
        AllocationPlan plan = strategy.allocate(c.amount(), c.numericReference(), candidates(loans, c));
        credits.save(ReceivedCredit.applied(c.inboundCreditId(), c.partyId(), c.amount(), c.receivedAt(),
                c.operationDate(), c.trackingKey(), c.numericReference(), plan));

        for (AllocationLine line : plan.lines()) {
            if (line.toLoan()) {
                AccountBalanceSnapshot loan = byId.get(line.creditAccountId());
                PaymentOrder order = orders.save(PaymentOrder.forReceivedCredit(loan.getCreditAccountId(),
                        loan.getObligorPartyId(), line.amount(), c.inboundCreditId(), line.sequence(), line.mode(),
                        c.receivedAt(), c.operationDate(), c.trackingKey(), loan.getBalanceVersion()));
                credits.save(CreditAllocation.of(c.inboundCreditId(), line, order.getPaymentOrderId()));
                events.publishPaymentApplied(order);
            } else {
                credits.save(CreditAllocation.of(c.inboundCreditId(), line, null));
                balances.move(c.partyId(), BalanceMovementType.EXCESS_FROM_CREDIT, line.amount(),
                        "CREDIT:" + c.inboundCreditId(), c.inboundCreditId(), null, null,
                        "Abono sin deuda que cubrir: queda a favor");
            }
        }
        log.info("Abono {} de {} aplicado: {} a créditos, {} a favor (referencia {})", c.trackingKey(), c.amount(),
                plan.toLoans(), plan.toCreditBalance(), plan.referenceStatus());
    }

    /**
     * The payer's loans that can take money: those alive with debt, and a written-off loan only when the transfer
     * names it (a recovery is not capped by the books, which no longer carry the debt).
     */
    private static List<LoanDebt> candidates(List<AccountBalanceSnapshot> loans, ReceivedCreditCommand c) {
        return loans.stream()
                .filter(l -> l.isAccountActive() || (l.isWrittenOff() && c.numericReference() != null
                        && Objects.equals(l.getPaymentReference(), c.numericReference())))
                .map(l -> new LoanDebt(l.getCreditAccountId(), l.getPaymentReference(), l.getActivatedAt(),
                        l.isWrittenOff() ? c.amount() : l.getTotalDebt()))
                .toList();
    }
}
