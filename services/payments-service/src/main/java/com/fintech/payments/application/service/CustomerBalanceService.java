package com.fintech.payments.application.service;

import com.fintech.payments.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.payments.application.port.out.CustomerBalanceRepository;
import com.fintech.payments.application.port.out.PaymentEventPublisher;
import com.fintech.payments.application.port.out.PaymentOrderRepository;
import com.fintech.payments.domain.AccountBalanceSnapshot;
import com.fintech.payments.domain.PaymentOrder;
import com.fintech.payments.domain.balance.BalanceMovementType;
import com.fintech.payments.domain.balance.CustomerBalance;
import com.fintech.payments.domain.balance.CustomerBalanceMovement;
import com.fintech.shared.time.BusinessClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The payer's balance in favor (saldo a favor): what it is, how it changes, and when it is used.
 *
 * <ul>
 *   <li>It grows with the part of a received credit that no loan owed, and with a share a loan rejected: the money
 *       is in the bank and nobody asked for it back.</li>
 *   <li>It is applied to the payer's due installment the day it falls due ({@code credit-portfolio.installment-due}),
 *       up to what the installment and the loan ask. No money moves in the bank: it was already there.</li>
 *   <li>Every change is a movement tied to its cause (the credit, the order, the loan), idempotent by that cause.</li>
 * </ul>
 */
@Service
@Transactional
public class CustomerBalanceService {

    private static final Logger log = LoggerFactory.getLogger(CustomerBalanceService.class);

    private final CustomerBalanceRepository balances;
    private final AccountBalanceSnapshotRepository snapshots;
    private final PaymentOrderRepository orders;
    private final PaymentEventPublisher events;

    public CustomerBalanceService(CustomerBalanceRepository balances, AccountBalanceSnapshotRepository snapshots,
                                  PaymentOrderRepository orders, PaymentEventPublisher events) {
        this.balances = balances;
        this.snapshots = snapshots;
        this.orders = orders;
        this.events = events;
    }

    /** Moves the balance of {@code partyId}; a cause already recorded is not recorded twice. */
    public BigDecimal move(UUID partyId, BalanceMovementType type, BigDecimal amount, String sourceKey,
                           UUID inboundCreditId, UUID paymentOrderId, UUID creditAccountId, String note) {
        CustomerBalance balance = balances.findByParty(partyId).orElseGet(() -> CustomerBalance.openFor(partyId));
        if (balances.movementExists(type, sourceKey)) {
            return balance.getBalance();
        }
        BigDecimal after = balance.move(type, amount);
        balances.save(balance);
        CustomerBalanceMovement movement = balances.save(CustomerBalanceMovement.record(partyId, type, amount, after,
                sourceKey, inboundCreditId, paymentOrderId, creditAccountId, note));
        events.publishBalanceMoved(movement);
        log.info("Saldo a favor party={} {} {} → {}", partyId, type, amount, after);
        return after;
    }

    /**
     * An installment of {@code creditAccountId} is due: the payer's balance in favor pays it, up to what the
     * installment and the loan owe. The event repeats every day the installment stays pending; the day is part of the
     * cause, and the balance only goes down, so nothing is paid twice.
     */
    public void applyToDueInstallment(UUID creditAccountId, UUID installmentId, BigDecimal installmentAmount) {
        Optional<AccountBalanceSnapshot> loan = snapshots.findByCreditAccountId(creditAccountId);
        if (loan.isEmpty() || !loan.get().isAccountActive() || installmentAmount == null) {
            return;
        }
        UUID party = loan.get().getObligorPartyId();
        Optional<CustomerBalance> balance = balances.findByParty(party).filter(CustomerBalance::hasBalance);
        if (balance.isEmpty()) {
            return;
        }
        BigDecimal amount = balance.get().getBalance().min(installmentAmount).min(loan.get().getTotalDebt());
        if (amount.signum() <= 0) {
            return;
        }
        LocalDate today = BusinessClock.current().today();
        String sourceKey = installmentId + ":" + today;
        if (orders.existsByExternalRef("BALANCE:" + sourceKey)) {
            return;
        }
        PaymentOrder order = orders.save(PaymentOrder.fromCreditBalance(creditAccountId, party, amount, sourceKey,
                loan.get().getBalanceVersion()));
        move(party, BalanceMovementType.APPLIED_TO_LOAN, amount, sourceKey, null, order.getPaymentOrderId(),
                creditAccountId, "Aplicado a la cuota que vence el " + today);
        events.publishPaymentApplied(order);
    }

    /**
     * The portfolio rejected an order: when its money came from a received credit or from the balance, it is still in
     * the bank, so it goes (back) to the payer's balance in favor instead of being lost.
     */
    public void onOrderRejected(PaymentOrder order, String reason) {
        BalanceMovementType type = switch (order.fundingSource()) {
            case SPEI_CREDIT -> BalanceMovementType.REJECTED_ALLOCATION;
            case CREDIT_BALANCE -> BalanceMovementType.APPLICATION_REJECTED;
            case DIRECT -> null;
        };
        if (type == null) {
            return;
        }
        move(order.getObligorPartyId(), type, order.getAmount(), "REJECTED:" + order.getPaymentOrderId(),
                order.getInboundCreditId(), order.getPaymentOrderId(), order.getCreditAccountId(), reason);
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(UUID partyId) {
        return balances.findByParty(partyId).map(CustomerBalance::getBalance).orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<CustomerBalanceMovement> movementsOf(UUID partyId) {
        return balances.movementsOf(partyId);
    }
}
