package com.fintech.payments.infrastructure.adapter.out.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment facts leave only once the order is committed: credit-portfolio answers in milliseconds and confirms
 * the order by its id, so an answer that beats the commit left the order PENDING for good (demo, 3 Nov).
 */
class KafkaPaymentEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    private final KafkaPaymentEventPublisher publisher = new KafkaPaymentEventPublisher(kafka);
    private final UUID account = UUID.randomUUID();

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction, payment-applied is sent after the commit and not before")
    void sendsAfterCommit() {
        stubSend();
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishPaymentApplied("order-1", account, new BigDecimal("913.19"), 45L);
        verify(kafka, never()).send(anyString(), anyString(), any());

        synchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(kafka).send(eq(KafkaPaymentEventPublisher.TOPIC_PAYMENT_APPLIED), eq(account.toString()), any());
    }

    @Test
    @DisplayName("a rolled back order sends nothing: no payment circulates that the database denies")
    void rollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishPaymentReversed("order-2", account, new BigDecimal("100.00"), "SPEI devuelto");
        synchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(kafka, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("with no transaction, the fact is sent at once")
    void sendsAtOnceWithoutTransaction() {
        stubSend();

        publisher.publishPaymentApplied("order-3", account, new BigDecimal("50.00"), 1L);

        verify(kafka).send(eq(KafkaPaymentEventPublisher.TOPIC_PAYMENT_APPLIED), eq(account.toString()), any());
    }

    private void stubSend() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<SendResult<String, Object>>());
    }

    private static List<TransactionSynchronization> synchronizations() {
        return TransactionSynchronizationManager.getSynchronizations();
    }
}
