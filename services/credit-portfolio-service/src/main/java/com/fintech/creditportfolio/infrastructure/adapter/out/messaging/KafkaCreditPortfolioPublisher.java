package com.fintech.creditportfolio.infrastructure.adapter.out.messaging;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.domain.event.BalanceUpdatedEvent;
import com.fintech.creditportfolio.domain.event.CreditAccountActivatedEvent;
import com.fintech.creditportfolio.domain.event.ChargeRejectedEvent;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import com.fintech.creditportfolio.domain.event.DispositionAuthorizedEvent;
import com.fintech.creditportfolio.domain.event.DispositionCompletedEvent;
import com.fintech.creditportfolio.domain.event.DispositionDeferredEvent;
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

@Component
public class KafkaCreditPortfolioPublisher implements CreditPortfolioEventPublisher {

    static final String TOPIC_ACCOUNT_ACTIVATED       = "credit-portfolio.credit-account-activated";
    static final String TOPIC_BALANCE_UPDATED         = "credit-portfolio.balance-updated";
    static final String TOPIC_PAYMENT_REJECTED        = "credit-portfolio.payment-rejected";
    static final String TOPIC_CHARGE_REJECTED         = "credit-portfolio.charge-rejected";
    static final String TOPIC_DELINQUENCY_UPDATED     = "credit-portfolio.delinquency-status-updated";
    static final String TOPIC_INSTALLMENT_DUE         = "credit-portfolio.installment-due";
    static final String TOPIC_INSTALLMENT_UPCOMING    = "credit-portfolio.installment-upcoming";
    /** El topic que `disbursement` escuchaba desde el principio y que nadie publicaba (BK-14). */
    static final String TOPIC_DISPOSITION_AUTHORIZED  = "credit-portfolio.disposition-authorized";
    static final String TOPIC_DISPOSITION_COMPLETED   = "credit-portfolio.disposition-completed";
    static final String TOPIC_DISPOSITION_DEFERRED    = "credit-portfolio.disposition-deferred";
    static final String TOPIC_RELIEF_GRANTED          = "credit-portfolio.relief-granted";
    static final String TOPIC_DISPOSITION_REJECTED    = "credit-portfolio.disposition-rejected";
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
}
