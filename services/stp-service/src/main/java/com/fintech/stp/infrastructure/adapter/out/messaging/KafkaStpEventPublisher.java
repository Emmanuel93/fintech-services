package com.fintech.stp.infrastructure.adapter.out.messaging;

import com.fintech.stp.application.port.out.StpEventPublisher;
import com.fintech.stp.domain.BanxicoResponseCode;
import com.fintech.stp.domain.ObservedVia;
import com.fintech.stp.domain.StpPaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica el resultado.
 *
 * <p>Lo que sale está traducido: {@code externalRef}, {@code banxicoCode} y {@code banxicoReason}.
 * Ni {@code claveRastreo} ni {@code firma} ni {@code empresa} cruzan la frontera con ese nombre —
 * salvo {@code trackingKey}, que es el identificador que operación necesita para rastrear ante
 * Banxico y que por eso viaja explícito.
 *
 * <p><strong>Se publica después del commit.</strong> Publicar dentro de la transacción deja
 * circulando hechos que la base puede contradecir si algo revierte después: aguas abajo se
 * contabilizaría como liquidada una orden que aquí sigue en vuelo.
 */
@Component
public class KafkaStpEventPublisher implements StpEventPublisher {

    static final String TOPIC_ORDER_ACCEPTED = "stp.order-accepted";
    static final String TOPIC_ORDER_REJECTED = "stp.order-rejected";
    static final String TOPIC_ORDER_SETTLED  = "stp.order-settled";
    static final String TOPIC_ORDER_RETURNED = "stp.order-returned";

    private static final Logger log = LoggerFactory.getLogger(KafkaStpEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaStpEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishOrderAccepted(StpPaymentOrder order) {
        send(TOPIC_ORDER_ACCEPTED, order, new StpOrderAcceptedPayload(
                order.getPaymentRequestId(), order.getCompanyId(),
                order.getStpOrderId(), order.getTrackingKey(), Instant.now()));
    }

    @Override
    public void publishOrderRejected(StpPaymentOrder order, BanxicoResponseCode code, String detail) {
        send(TOPIC_ORDER_REJECTED, order, new StpOrderRejectedPayload(
                order.getPaymentRequestId(), order.getCompanyId(), code.code(), code.name(),
                detail, code.isRetryable(), Instant.now()));
    }

    @Override
    public void publishOrderSettled(StpPaymentOrder order, Instant settledAt, String cepUrl,
                                    String cepBeneficiaryName, boolean beneficiaryNameMatches,
                                    ObservedVia observedVia) {
        send(TOPIC_ORDER_SETTLED, order, new StpOrderSettledPayload(
                order.getPaymentRequestId(), order.getCompanyId(), order.getTrackingKey(),
                cepUrl, cepBeneficiaryName, beneficiaryNameMatches, settledAt,
                observedVia.name(), Instant.now()));
    }

    @Override
    public void publishOrderReturned(StpPaymentOrder order, String status, String returnCauseCode,
                                     ObservedVia observedVia) {
        send(TOPIC_ORDER_RETURNED, order, new StpOrderReturnedPayload(
                order.getPaymentRequestId(), order.getCompanyId(), order.getTrackingKey(),
                status, returnCauseCode, observedVia.name(), Instant.now()));
    }

    private static final long SEND_TIMEOUT_SECONDS = 10L;

    /**
     * El payload se arma ahora —con la entidad cargada— y sólo el envío se difiere al commit, para
     * que la sincronización no dependa de una entidad ya desconectada.
     */
    private void send(String topic, StpPaymentOrder order, Object payload) {
        String key = order.getPaymentRequestId().toString();
        String trackingKey = order.getTrackingKey();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(topic, key, trackingKey, payload);
                }
            });
            return;
        }
        doSend(topic, key, trackingKey, payload);
    }

    private void doSend(String topic, String key, String trackingKey, Object payload) {
        try {
            kafkaTemplate.send(topic, key, payload).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("{} published paymentRequestId={} trackingKey={}", topic, key, trackingKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} publish interrumpido paymentRequestId={}", topic, key);
        } catch (ExecutionException | TimeoutException e) {
            // El estado en base ya está confirmado y no se puede deshacer. Queda ruidoso para que
            // operación lo reprocese: GET /api/v1/stp/orders/{id} sigue siendo la fuente de verdad.
            log.error("{} publish FALLÓ tras el commit paymentRequestId={} trackingKey={}: {} — "
                            + "requiere reproceso manual", topic, key, trackingKey, e.getMessage());
        }
    }
}
