package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.infrastructure.config.DisbursementTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica los cuatro hechos del servicio. Ninguno es un comando: nadie de aquí le dice a nadie qué
 * hacer, sólo qué pasó.
 *
 * <p><strong>Se publica después del commit, no antes.</strong> Publicar dentro de la transacción
 * significa que un rollback posterior deja circulando un hecho que la base contradice: aguas abajo
 * se contabiliza un pago liquidado que aquí sigue en vuelo. El envío se registra como
 * {@link TransactionSynchronization} y sólo sale si la transacción confirmó.
 *
 * <p>Queda una ventana honesta: si el broker rechaza el mensaje <em>después</em> del commit, el
 * hecho se pierde y sólo queda el {@code ERROR} en el log. Se acepta porque es el patrón vigente en
 * todo el monorepo; el estado real siempre es recuperable consultando
 * {@code GET /api/v1/disbursements/{id}}, que es la fuente de verdad.
 */
@Component
public class KafkaDisbursementEventPublisher implements DisbursementEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDisbursementEventPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final DisbursementTopicProperties topics;

    public KafkaDisbursementEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                           DisbursementTopicProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    @Override
    public void publishAccepted(DisbursementOrder order) {
        send(topics.getAccepted(), order.getDisbursementId(), new DisbursementAcceptedPayload(
                order.getDisbursementId(), order.getCompanyId(), order.getSourceSystem(),
                order.getSourceType(), order.getSourceReference(), order.getSourceEventId(),
                order.getSourceMetadata(), order.getAmount(), order.getCurrency(),
                order.getProvider(), order.getExternalRef(), order.getCorrelationId(), Instant.now()));
    }

    @Override
    public void publishCompleted(DisbursementOrder order, boolean beneficiaryNameMatches) {
        send(topics.getCompleted(), order.getDisbursementId(), new DisbursementCompletedPayload(
                order.getDisbursementId(), order.getCompanyId(), order.getSourceSystem(),
                order.getSourceType(), order.getSourceReference(), order.getSourceEventId(),
                order.getSourceMetadata(), order.getAmount(), order.getCurrency(),
                order.getProvider(), order.getExternalRef(), order.getCepUrl(),
                beneficiaryNameMatches, order.getSettledAt(), order.getCorrelationId(), Instant.now()));
    }

    @Override
    public void publishFailed(DisbursementOrder order) {
        send(topics.getFailed(), order.getDisbursementId(), new DisbursementFailedPayload(
                order.getDisbursementId(), order.getCompanyId(), order.getSourceSystem(),
                order.getSourceType(), order.getSourceReference(), order.getSourceEventId(),
                order.getSourceMetadata(), order.getAmount(), order.getCurrency(),
                order.getProvider(), order.getStatus(), order.getFailureCode(),
                order.getFailureReason(), order.getCorrelationId(), Instant.now()));
    }

    @Override
    public void publishReturned(DisbursementOrder order) {
        send(topics.getReturned(), order.getDisbursementId(), new DisbursementReturnedPayload(
                order.getDisbursementId(), order.getCompanyId(), order.getSourceSystem(),
                order.getSourceType(), order.getSourceReference(), order.getSourceEventId(),
                order.getSourceMetadata(), order.getAmount(), order.getCurrency(),
                order.getProvider(), order.getExternalRef(), order.getFailureReason(),
                order.getCorrelationId(), Instant.now()));
    }

    /**
     * El payload se arma <strong>ahora</strong> —con la entidad todavía cargada— y sólo el envío se
     * difiere al commit. Así la sincronización no depende de una entidad que para entonces ya está
     * desconectada.
     *
     * <p>La clave de partición es el {@code disbursementId}: garantiza el orden de los hechos de una
     * misma orden, que es la única garantía de orden que el negocio necesita.
     */
    private void send(String topic, UUID disbursementId, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(topic, disbursementId, payload);
                }
            });
            return;
        }
        doSend(topic, disbursementId, payload);
    }

    private void doSend(String topic, UUID disbursementId, Object payload) {
        try {
            kafkaTemplate.send(topic, disbursementId.toString(), payload)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("{} published disbursementId={}", topic, disbursementId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} publish interrumpido disbursementId={}", topic, disbursementId);
        } catch (ExecutionException | TimeoutException e) {
            // El estado en base ya está confirmado: no se puede deshacer. Lo único honesto es
            // dejarlo visible y ruidoso para que operación lo reprocese.
            log.error("{} publish FALLÓ tras el commit disbursementId={}: {} — el estado en base es "
                            + "correcto pero nadie aguas abajo se enteró; requiere reproceso manual",
                    topic, disbursementId, e.getMessage());
        }
    }
}
