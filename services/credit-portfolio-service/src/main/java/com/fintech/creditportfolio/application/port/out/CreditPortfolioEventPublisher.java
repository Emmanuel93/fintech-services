package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.ReliefGrantedEvent;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
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
    /** Autorizada y pendiente de pago. Quien paga es `disbursement`. */
    void publishDispositionAuthorized(DispositionAuthorizedEvent event);
    void publishDispositionCompleted(DispositionCompletedEvent event);
    /** Apoyo otorgado: risk marca forborne y collections no abre caso durante la vigencia. */
    void publishReliefGranted(ReliefGrantedEvent event);
    /** El titular difirió una compra: charges reversa lo devengado como revolvente (BK-26). */
    void publishDispositionDeferred(DispositionDeferredEvent event);
    void publishDispositionRejected(DispositionRejectedEvent event);
}
