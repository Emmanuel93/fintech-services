package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import com.fintech.creditportfolio.domain.event.InstallmentDueEvent;
import com.fintech.creditportfolio.domain.event.InstallmentUpcomingEvent;

import java.math.BigDecimal;
import java.util.UUID;

public interface CreditPortfolioEventPublisher {
    void publishCreditAccountActivated(CreditAccountActivatedEvent event);
    void publishBalanceUpdated(BalanceUpdatedEvent event);
    void publishPaymentRejected(String sourceEventId, UUID creditAccountId,
                                BigDecimal rejectedAmount, String reason);
    void publishChargeRejected(String sourceEventId, UUID creditAccountId,
                               String chargeType, BigDecimal rejectedAmount, String reason);
    void publishDelinquencyStatusUpdated(DelinquencyStatusUpdatedEvent event);
    void publishInstallmentDue(InstallmentDueEvent event);
    void publishInstallmentUpcoming(InstallmentUpcomingEvent event);
    void publishDispositionCompleted(DispositionCompletedEvent event);
    void publishDispositionRejected(DispositionRejectedEvent event);
}
