package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountCancelledEvent;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.ReliefGrantedEvent;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
import com.fintech.creditportfolio.domain.event.DispositionFailedEvent;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import com.fintech.creditportfolio.domain.event.InstallmentDueEvent;
import com.fintech.creditportfolio.domain.event.InstallmentUpcomingEvent;

import java.math.BigDecimal;
import java.util.UUID;
import com.fintech.creditportfolio.domain.event.AccountRestructuredEvent;

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

    /** Cartera cerró el día: closing corta con este aviso. Espera la confirmación del broker; si no llega, lanza. */
    void publishDayClosed(com.fintech.creditportfolio.domain.event.DayClosedEvent event);

    /**
     * Una cuenta que el cierre de su día dejó fuera ya tiene su interés aplicado y cuadrado: closing suelta
     * su corte. Espera al broker, después de confirmar lo publicado antes.
     */
    void publishAccountDayClosed(com.fintech.creditportfolio.domain.event.AccountDayClosedEvent event);
    /** Autorizada y pendiente de pago. Quien paga es `disbursement`. */
    void publishDispositionAuthorized(DispositionAuthorizedEvent event);
    void publishDispositionCompleted(DispositionCompletedEvent event);
    /** Apoyo otorgado: risk marca forborne y collections no abre caso durante la vigencia. */
    void publishReliefGranted(ReliefGrantedEvent event);

    /** A restructure took effect: charges accrues the new rate from its effective date. */
    void publishAccountRestructured(AccountRestructuredEvent event);
    /** El titular difirió una compra: charges reversa lo devengado como revolvente (BK-26). */
    void publishDispositionDeferred(DispositionDeferredEvent event);
    void publishDispositionRejected(DispositionRejectedEvent event);

    /** A disposition's payment did not happen, for good, and was compensated (D63). */
    void publishDispositionFailed(DispositionFailedEvent event);

    /** An installment credit whose only disbursement could not happen was cancelled; each service compensates (D63). */
    void publishCreditAccountCancelled(CreditAccountCancelledEvent event);
}
