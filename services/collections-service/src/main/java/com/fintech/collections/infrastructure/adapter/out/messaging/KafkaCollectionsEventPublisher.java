package com.fintech.collections.infrastructure.adapter.out.messaging;

import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Component
public class KafkaCollectionsEventPublisher implements CollectionsEventPublisher {

    static final String TOPIC_PRE_DUE_REMINDER      = "collections.pre-due-reminder-triggered";
    static final String TOPIC_CASE_CREATED           = "collections.case-created";
    static final String TOPIC_CASE_ESCALATED         = "collections.case-escalated";
    static final String TOPIC_CONTACT_ATTEMPT        = "collections.contact-attempt-registered";
    static final String TOPIC_PROMISE_MADE           = "collections.payment-promise-made";
    static final String TOPIC_PROMISE_BROKEN         = "collections.payment-promise-broken";
    static final String TOPIC_AGREEMENT_PROPOSED     = "collections.agreement-proposed";
    static final String TOPIC_AGREEMENT_EXECUTED     = "collections.agreement-executed";
    static final String TOPIC_WRITE_OFF_REQUESTED    = "collections.write-off-requested";
    static final String TOPIC_WRITE_OFF_EXECUTED     = "collections.write-off-executed";
    static final String TOPIC_BUREAU_REPORT_SUBMITTED = "collections.bureau-report-submitted";
    static final String TOPIC_RECOVERY_PAYMENT        = "collections.recovery-payment-applied";
    static final String TOPIC_DUNNING_REQUESTED       = "collections.dunning-requested";
    static final String TOPIC_PAYMENT_THANKS          = "collections.payment-thanks";

    private static final Logger log = LoggerFactory.getLogger(KafkaCollectionsEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaCollectionsEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishPreDueReminderTriggered(UUID creditAccountId, UUID obligorPartyId,
                                                LocalDate dueDate, BigDecimal installmentAmount) {
        var payload = new PreDueReminderTriggeredPayload(creditAccountId, obligorPartyId, dueDate, installmentAmount, Instant.now());
        send(TOPIC_PRE_DUE_REMINDER, creditAccountId.toString(), payload, "pre-due-reminder-triggered");
    }

    @Override
    public void publishCollectionCaseCreated(CollectionCase c) {
        var payload = new CollectionCaseEventPayload(c.getCaseId(), c.getCreditAccountId(), c.getObligorPartyId(),
                c.getCurrentBucket().name(), null, c.getDaysDelinquent(), c.getTotalDebt(), c.getStrategy(), Instant.now());
        send(TOPIC_CASE_CREATED, c.getCreditAccountId().toString(), payload, "case-created");
    }

    @Override
    public void publishCollectionCaseEscalated(CollectionCase c, String previousBucket) {
        var payload = new CollectionCaseEventPayload(c.getCaseId(), c.getCreditAccountId(), c.getObligorPartyId(),
                c.getCurrentBucket().name(), previousBucket, c.getDaysDelinquent(), c.getTotalDebt(), c.getStrategy(), Instant.now());
        send(TOPIC_CASE_ESCALATED, c.getCreditAccountId().toString(), payload, "case-escalated");
    }

    @Override
    public void publishContactAttemptRegistered(ContactAttempt attempt) {
        var payload = new ContactAttemptRegisteredPayload(attempt.getAttemptId(), attempt.getCaseId(),
                attempt.getChannel().name(), attempt.getResult().name(), attempt.getAgentId(), attempt.getAttemptedAt());
        send(TOPIC_CONTACT_ATTEMPT, attempt.getCaseId().toString(), payload, "contact-attempt-registered");
    }

    @Override
    public void publishDunningRequested(CollectionCase c, DunningStep step) {
        var payload = new DunningRequestedPayload(c.getCaseId(), c.getCreditAccountId(), c.getObligorPartyId(),
                step.name(), step.dia(), c.getDaysDelinquent(), c.getCurrentBucket().name(),
                c.getTotalDebt(), Instant.now());
        send(TOPIC_DUNNING_REQUESTED, c.getCaseId().toString(), payload, "dunning-requested");
    }

    @Override
    public void publishPaymentThanks(CollectionCase c, BigDecimal amount) {
        var payload = new PaymentThanksPayload(c.getCaseId(), c.getCreditAccountId(), c.getObligorPartyId(),
                amount, c.getDaysDelinquent(), Instant.now());
        send(TOPIC_PAYMENT_THANKS, c.getCaseId().toString(), payload, "payment-thanks");
    }

    @Override
    public void publishPaymentPromiseMade(PaymentPromise promise) {
        var payload = new PaymentPromiseEventPayload(promise.getPromiseId(), promise.getCaseId(),
                promise.getAmount(), promise.getPromisedDate(), promise.getRecordedBy(), Instant.now());
        send(TOPIC_PROMISE_MADE, promise.getCaseId().toString(), payload, "payment-promise-made");
    }

    @Override
    public void publishPaymentPromiseBroken(PaymentPromise promise) {
        var payload = new PaymentPromiseEventPayload(promise.getPromiseId(), promise.getCaseId(),
                promise.getAmount(), promise.getPromisedDate(), promise.getRecordedBy(), Instant.now());
        send(TOPIC_PROMISE_BROKEN, promise.getCaseId().toString(), payload, "payment-promise-broken");
    }

    @Override
    public void publishCollectionAgreementProposed(CollectionAgreement agreement) {
        var payload = toAgreementPayload(agreement);
        send(TOPIC_AGREEMENT_PROPOSED, agreement.getCreditAccountId().toString(), payload, "agreement-proposed");
    }

    @Override
    public void publishCollectionAgreementExecuted(CollectionAgreement agreement) {
        var payload = toAgreementPayload(agreement);
        send(TOPIC_AGREEMENT_EXECUTED, agreement.getCreditAccountId().toString(), payload, "agreement-executed");
    }

    private CollectionAgreementEventPayload toAgreementPayload(CollectionAgreement agreement) {
        return new CollectionAgreementEventPayload(agreement.getAgreementId(), agreement.getCaseId(),
                agreement.getCreditAccountId(), agreement.getObligorPartyId(), agreement.getType().name(),
                agreement.getForgivenAmount(), agreement.getNewTerms(),
                agreement.getAuthorizedBy(), agreement.getAuthorizationRef(), Instant.now());
    }

    @Override
    public void publishWriteOffRequested(UUID caseId, UUID creditAccountId, BigDecimal totalDebt,
                                          int daysDelinquent, String reason, String requestedBy) {
        var payload = new WriteOffRequestedPayload(caseId, creditAccountId, totalDebt, daysDelinquent,
                reason, requestedBy, Instant.now());
        send(TOPIC_WRITE_OFF_REQUESTED, creditAccountId.toString(), payload, "write-off-requested");
    }

    @Override
    public void publishWriteOffExecuted(WriteOffRecord record) {
        var payload = new WriteOffExecutedPayload(record.getWriteOffId().toString(), record.getCreditAccountId(),
                record.getWriteOffId(), record.getCaseId(), record.getObligorPartyId(),
                record.getPrincipalWrittenOff(), record.getInterestWrittenOff(), record.getPenaltyWrittenOff(),
                record.getTotalWrittenOff(), record.getAuthorizedBy(), record.getAuthorizationRef(),
                record.getReason().name(), Instant.now());
        send(TOPIC_WRITE_OFF_EXECUTED, record.getCreditAccountId().toString(), payload, "write-off-executed");
    }

    @Override
    public void publishBureauReportSubmitted(BureauReport report) {
        var payload = new BureauReportSubmittedPayload(report.getReportId(), report.getCreditAccountId(),
                report.getObligorPartyId(), report.getEventType().name(), report.getAmountReported(),
                report.getBureauReference(), Instant.now());
        send(TOPIC_BUREAU_REPORT_SUBMITTED, report.getCreditAccountId().toString(), payload, "bureau-report-submitted");
    }

    @Override
    public void publishRecoveryPaymentApplied(UUID writeOffId, UUID creditAccountId,
                                               BigDecimal recoveredAmount, String paymentMethod) {
        var payload = new RecoveryPaymentAppliedPayload(writeOffId, creditAccountId, recoveredAmount, paymentMethod, Instant.now());
        send(TOPIC_RECOVERY_PAYMENT, creditAccountId.toString(), payload, "recovery-payment-applied");
    }

    private void send(String topic, String key, Object payload, String label) {
        kafkaTemplate.send(topic, key, payload)
                .whenComplete((r, ex) -> {
                    if (ex != null) log.error("{} publish failed key={}: {}", label, key, ex.getMessage());
                    else log.debug("{} published key={}", label, key);
                });
    }
}
