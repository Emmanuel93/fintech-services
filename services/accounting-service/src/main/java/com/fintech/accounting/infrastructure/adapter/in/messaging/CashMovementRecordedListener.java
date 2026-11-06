package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.port.out.TreasuryCashBookRepository;
import com.fintech.accounting.application.service.TreasuryPostingService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Keeps treasury's cash book next to the ledger, so the close can reconcile 1101 against it. */
@Component
public class CashMovementRecordedListener {

    private final TreasuryCashBookRepository cashBook;
    private final TreasuryPostingService bankSide;

    public CashMovementRecordedListener(TreasuryCashBookRepository cashBook, TreasuryPostingService bankSide) {
        this.cashBook = cashBook;
        this.bankSide = bankSide;
    }

    @KafkaListener(topics = "banking.cash-movement-recorded",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "cashMovementRecordedListenerContainerFactory")
    public void onMessage(CashMovementRecordedPayload p) {
        if (p.movementId() == null || p.amount() == null || p.amount().signum() <= 0 || p.businessDate() == null) {
            return;
        }
        cashBook.record(p.movementId(), p.movementType(), p.direction(), p.amount(), p.businessDate(), p.reference(),
                p.sourceEventId(), p.portfolioDate(), p.trackingKey());
        bankSide.onCashMovement(p.movementId(), p.movementType(), p.sourceEventId(), p.amount(), p.businessDate(),
                p.reference());
    }
}
