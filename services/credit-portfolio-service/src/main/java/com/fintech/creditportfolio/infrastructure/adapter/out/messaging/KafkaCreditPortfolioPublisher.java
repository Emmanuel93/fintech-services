package com.fintech.creditportfolio.infrastructure.adapter.out.messaging;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountCancelledEvent;
import com.fintech.creditportfolio.domain.event.ChargeRejectedEvent;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
import com.fintech.creditportfolio.domain.event.DispositionFailedEvent;
import com.fintech.creditportfolio.domain.event.ReliefGrantedEvent;
import com.fintech.creditportfolio.domain.event.DispositionRejectedEvent;
import com.fintech.creditportfolio.domain.event.InstallmentDueEvent;
import com.fintech.creditportfolio.domain.event.InstallmentUpcomingEvent;
import com.fintech.creditportfolio.domain.event.PaymentRejectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import com.fintech.creditportfolio.domain.event.AccountRestructuredEvent;
import java.util.concurrent.ExecutionException;

@Component
public class KafkaCreditPortfolioPublisher implements CreditPortfolioEventPublisher {

    static final String TOPIC_ACCOUNT_ACTIVATED       = "credit-portfolio.credit-account-activated";
    static final String TOPIC_ACCOUNT_RESTRUCTURED    = "credit-portfolio.account-restructured";
    static final String TOPIC_BALANCE_UPDATED         = "credit-portfolio.balance-updated";
    static final String TOPIC_PAYMENT_REJECTED        = "credit-portfolio.payment-rejected";
    static final String TOPIC_CHARGE_REJECTED         = "credit-portfolio.charge-rejected";
    static final String TOPIC_DELINQUENCY_UPDATED     = "credit-portfolio.delinquency-status-updated";
    static final String TOPIC_INSTALLMENT_DUE         = "credit-portfolio.installment-due";
    static final String TOPIC_INSTALLMENT_UPCOMING    = "credit-portfolio.installment-upcoming";
    public static final String TOPIC_DAY_CLOSED       = "credit-portfolio.day-closed";
    public static final String TOPIC_ACCOUNT_DAY_CLOSED = "credit-portfolio.account-day-closed";
    /** El topic que `disbursement` escuchaba desde el principio y que nadie publicaba (BK-14). */
    static final String TOPIC_DISPOSITION_AUTHORIZED  = "credit-portfolio.disposition-authorized";
    static final String TOPIC_DISPOSITION_COMPLETED   = "credit-portfolio.disposition-completed";
    static final String TOPIC_DISPOSITION_DEFERRED    = "credit-portfolio.disposition-deferred";
    static final String TOPIC_RELIEF_GRANTED          = "credit-portfolio.relief-granted";
    static final String TOPIC_DISPOSITION_REJECTED    = "credit-portfolio.disposition-rejected";
    static final String TOPIC_DISPOSITION_FAILED      = "credit-portfolio.disposition-failed";
    static final String TOPIC_ACCOUNT_CANCELLED       = "credit-portfolio.credit-account-cancelled";
    private static final Logger log = LoggerFactory.getLogger(KafkaCreditPortfolioPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaCreditPortfolioPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishCreditAccountActivated(CreditAccountActivatedEvent event) {
        kafkaTemplate.send(TOPIC_ACCOUNT_ACTIVATED, event.getContractId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("CreditAccountActivated publish failed contractId={}: {}",
                                event.getContractId(), ex.getMessage());
                    } else {
                        log.info("CreditAccountActivated published creditAccountId={} contractId={}",
                                event.getCreditAccountId(), event.getContractId());
                    }
                });
    }

    @Override
    public void publishBalanceUpdated(BalanceUpdatedEvent event) {
        kafkaTemplate.send(TOPIC_BALANCE_UPDATED, event.getCreditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("BalanceUpdated publish failed creditAccountId={}: {}",
                                event.getCreditAccountId(), ex.getMessage());
                    } else {
                        log.info("BalanceUpdated published creditAccountId={} trigger={} version={}",
                                event.getCreditAccountId(), event.getTriggerEvent(), event.getBalanceVersion());
                    }
                });
    }

    @Override
    public void publishPaymentRejected(String sourceEventId, UUID creditAccountId,
                                        BigDecimal rejectedAmount, String reason) {
        var event = new PaymentRejectedEvent(sourceEventId, creditAccountId, rejectedAmount, reason);
        kafkaTemplate.send(TOPIC_PAYMENT_REJECTED, creditAccountId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("PaymentRejected publish failed creditAccountId={}: {}", creditAccountId, ex.getMessage());
                    } else {
                        log.warn("PaymentRejected published sourceEventId={} creditAccountId={} reason={}",
                                sourceEventId, creditAccountId, reason);
                    }
                });
    }

    @Override
    public void publishChargeRejected(String sourceEventId, UUID creditAccountId,
                                       String chargeType, BigDecimal rejectedAmount, String reason) {
        var event = new ChargeRejectedEvent(sourceEventId, creditAccountId, chargeType, rejectedAmount, reason);
        kafkaTemplate.send(TOPIC_CHARGE_REJECTED, creditAccountId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("ChargeRejected publish failed creditAccountId={}: {}", creditAccountId, ex.getMessage());
                    } else {
                        log.warn("ChargeRejected published sourceEventId={} creditAccountId={} type={} reason={}",
                                sourceEventId, creditAccountId, chargeType, reason);
                    }
                });
    }

    @Override
    public void publishDelinquencyStatusUpdated(DelinquencyStatusUpdatedEvent event) {
        kafkaTemplate.send(TOPIC_DELINQUENCY_UPDATED, event.getCreditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("DelinquencyStatusUpdated publish failed creditAccountId={}: {}",
                                event.getCreditAccountId(), ex.getMessage());
                    } else {
                        log.info("DelinquencyStatusUpdated published creditAccountId={} days={}",
                                event.getCreditAccountId(), event.getDaysDelinquent());
                    }
                });
    }

    @Override
    public void publishInstallmentDue(InstallmentDueEvent event) {
        kafkaTemplate.send(TOPIC_INSTALLMENT_DUE, event.getCreditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("InstallmentDue publish failed installmentId={}: {}",
                                event.getInstallmentId(), ex.getMessage());
                    } else {
                        log.info("InstallmentDue published installmentId={} creditAccountId={} dueDate={}",
                                event.getInstallmentId(), event.getCreditAccountId(), event.getDueDate());
                    }
                });
    }

    @Override
    public void publishInstallmentUpcoming(InstallmentUpcomingEvent event) {
        kafkaTemplate.send(TOPIC_INSTALLMENT_UPCOMING, event.getCreditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("InstallmentUpcoming publish failed installmentId={}: {}",
                                event.getInstallmentId(), ex.getMessage());
                    } else {
                        log.info("InstallmentUpcoming published installmentId={} creditAccountId={} dueDate={}",
                                event.getInstallmentId(), event.getCreditAccountId(), event.getDueDate());
                    }
                });
    }

    @Override
    public void publishDispositionAuthorized(DispositionAuthorizedEvent event) {
        kafkaTemplate.send(TOPIC_DISPOSITION_AUTHORIZED, event.creditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Si esto falla, la disposición se queda PROCESSING y nadie la paga. Es el
                        // desenlace correcto: preferible una disposición visiblemente detenida a
                        // una marcada como pagada sin que saliera un peso.
                        log.error("DispositionAuthorized publish failed dispositionId={}: {}",
                                event.dispositionId(), ex.getMessage());
                    } else {
                        log.info("DispositionAuthorized publicado dispositionId={} creditAccountId={} monto={}",
                                event.dispositionId(), event.creditAccountId(), event.amount());
                    }
                });
    }

    @Override
    public void publishReliefGranted(ReliefGrantedEvent event) {
        kafkaTemplate.send(TOPIC_RELIEF_GRANTED, event.creditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Si esto se pierde, la cuenta queda con los vencimientos corridos pero SIN
                        // marcar forborne: reserva de menos y una cuenta apoyada que cobranza puede
                        // seguir persiguiendo.
                        log.error("ReliefGranted publish failed cuenta={} programa={}: {}",
                                event.creditAccountId(), event.reliefProgramId(), ex.getMessage());
                    }
                });
    }

    @Override
    public void publishAccountRestructured(AccountRestructuredEvent event) {
        // Synchronous: if the broker does not take it, the agreement's transaction rolls back and the
        // agreement-executed message is redelivered. A restructure that charges never hears about keeps
        // accruing the old rate, which is exactly the defect this event exists to fix.
        try {
            kafkaTemplate.send(TOPIC_ACCOUNT_RESTRUCTURED, event.creditAccountId().toString(), event).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("No se pudo publicar la reestructura de " + event.creditAccountId(), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("No se pudo publicar la reestructura de " + event.creditAccountId()
                    + ": " + e.getCause().getMessage(), e);
        }
    }

    @Override
    public void publishDispositionDeferred(DispositionDeferredEvent event) {
        kafkaTemplate.send(TOPIC_DISPOSITION_DEFERRED, event.creditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Si esto se pierde, el plan existe pero el interés devengado como
                        // revolvente NO se reversa: el cliente paga su MSI y además el interés de
                        // los días previos. Es un cobro de más, y por eso se registra como error.
                        log.error("DispositionDeferred publish failed dispositionId={}: {}",
                                event.dispositionId(), ex.getMessage());
                    } else {
                        log.info("DispositionDeferred publicado dispositionId={} plazo={} tasa={}",
                                event.dispositionId(), event.termPeriods(), event.nominalRate());
                    }
                });
    }

    @Override
    public void publishDispositionCompleted(DispositionCompletedEvent event) {
        kafkaTemplate.send(TOPIC_DISPOSITION_COMPLETED, event.getCreditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("DispositionCompleted publish failed dispositionId={}: {}",
                                event.getDispositionId(), ex.getMessage());
                    } else {
                        log.info("DispositionCompleted published dispositionId={} creditAccountId={} type={} amount={}",
                                event.getDispositionId(), event.getCreditAccountId(),
                                event.getDispositionType(), event.getAmount());
                    }
                });
    }

    @Override
    public void publishDispositionRejected(DispositionRejectedEvent event) {
        kafkaTemplate.send(TOPIC_DISPOSITION_REJECTED, event.getCreditAccountId().toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("DispositionRejected publish failed creditAccountId={}: {}",
                                event.getCreditAccountId(), ex.getMessage());
                    } else {
                        log.warn("DispositionRejected published sourceEventId={} creditAccountId={} reason={}",
                                event.getSourceEventId(), event.getCreditAccountId(), event.getReason());
                    }
                });
    }

    @Override
    public void publishDayClosed(com.fintech.creditportfolio.domain.event.DayClosedEvent event) {
        try {
            // Primero, que el broker confirme todo lo publicado antes —saldos, mora—: closing toma su
            // foto de offsets al recibir este aviso, y la foto tiene que incluirlo.
            kafkaTemplate.flush();
            // Se espera al broker: sin este aviso closing no corta.
            kafkaTemplate.send(TOPIC_DAY_CLOSED, event.businessDate().toString(), event)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al avisar el cierre del día " + event.businessDate(), e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("No se pudo avisar el cierre del día " + event.businessDate(), e);
        }
    }

    @Override
    public void publishAccountDayClosed(com.fintech.creditportfolio.domain.event.AccountDayClosedEvent event) {
        try {
            // Como el cierre del día: closing toma su foto de offsets al recibirlo, y tiene que incluir los
            // saldos de esta cuenta.
            kafkaTemplate.flush();
            kafkaTemplate.send(TOPIC_ACCOUNT_DAY_CLOSED, event.creditAccountId().toString(), event)
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al soltar la cuenta " + event.creditAccountId(), e);
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("No se pudo soltar la cuenta " + event.creditAccountId()
                                            + " del " + event.businessDate(), e);
        }
    }

    @Override
    public void publishDispositionFailed(DispositionFailedEvent event) {
        send(TOPIC_DISPOSITION_FAILED, event.creditAccountId(), event, "DispositionFailed dispositionId=" + event.dispositionId());
    }

    @Override
    public void publishCreditAccountCancelled(CreditAccountCancelledEvent event) {
        send(TOPIC_ACCOUNT_CANCELLED, event.creditAccountId(), event, "CreditAccountCancelled creditAccountId="
                + event.creditAccountId());
    }

    /** Keyed by the credit: its facts reach each consumer in the order they happened. */
    private void send(String topic, UUID creditAccountId, Object event, String what) {
        kafkaTemplate.send(topic, creditAccountId.toString(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("{} publish failed: {}", what, ex.getMessage());
                    } else {
                        log.info("{} publicado en {}", what, topic);
                    }
                });
    }
}
