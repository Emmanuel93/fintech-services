package com.fintech.charges.infrastructure.adapter.in.messaging;

import com.fintech.charges.application.service.DeferralInterestReversalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * El titular difirió una compra; el interés que devengó como revolvente se reversa.
 *
 * <p>Sin esto, un cliente con meses sin intereses pagaría su plan <b>y además</b> el interés de los
 * días entre la compra y el diferimiento. Es un cobro de más, y silencioso.
 */
@Component
public class DispositionDeferredListener {

    private static final Logger log = LoggerFactory.getLogger(DispositionDeferredListener.class);

    private final DeferralInterestReversalService reversas;

    public DispositionDeferredListener(DeferralInterestReversalService reversas) {
        this.reversas = reversas;
    }

    @KafkaListener(
            topics = "credit-portfolio.disposition-deferred",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "dispositionDeferredListenerContainerFactory")
    public void onDispositionDeferred(DispositionDeferredPayload event) {
        log.debug("disposition-deferred recibido disposición={} importe={} de {} a {}",
                event.dispositionId(), event.amount(), event.revolvingDesde(), event.revolvingHasta());

        reversas.reversarPorDiferimiento(event.creditAccountId(), event.dispositionId(),
                event.amount(), event.revolvingDesde(), event.revolvingHasta());
    }
}
