package com.fintech.disbursement.infrastructure.adapter.out.messaging;

import com.fintech.disbursement.application.port.out.ProviderDispatchPort;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.Provider;
import com.fintech.disbursement.infrastructure.config.DisbursementTopicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Entrega la orden al conector por Kafka, un topic por proveedor.
 *
 * <p>Bloquea hasta que el broker confirma. Es deliberado: el job de despacho necesita saber si la
 * orden salió para decidir si reintenta. Un {@code send()} sin esperar dejaría la orden en
 * {@code DISPATCHED} sin que nadie la haya recibido.
 */
@Component
public class KafkaProviderDispatchAdapter implements ProviderDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaProviderDispatchAdapter.class);
    private static final long SEND_TIMEOUT_SECONDS = 10L;

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final DisbursementTopicProperties topics;

    public KafkaProviderDispatchAdapter(KafkaTemplate<String, Object> kafkaTemplate,
                                        DisbursementTopicProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    @Override
    public void dispatch(DisbursementOrder order, Provider provider) {
        String topic = topics.getProvider().get(provider.name());
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException("No hay topic configurado para el proveedor " + provider
                    + " (fintech.disbursement.topics.provider." + provider + ")");
        }

        PaymentOrderRequestedPayload payload = new PaymentOrderRequestedPayload(
                order.getDisbursementId(), order.getCompanyId(), order.getAmount(), order.getCurrency(),
                order.getBeneficiary().getName(), order.getBeneficiary().getAccount(),
                order.getBeneficiary().getAccountType(), order.getBeneficiary().getTaxId(),
                order.getBeneficiary().getInstitution(), order.getConcept(),
                order.getNumericReference(), null, order.getCorrelationId(), Instant.now());

        try {
            kafkaTemplate.send(topic, order.getDisbursementId().toString(), payload)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Orden entregada a {} topic={} disbursementId={}",
                    provider, topic, order.getDisbursementId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido al entregar al conector " + provider, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(
                    "No se pudo entregar al conector " + provider + ": " + e.getMessage(), e);
        }
    }
}
