package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.port.in.CloseCutoffCycleUseCase;
import com.fintech.creditportfolio.application.port.in.CloseCutoffCycleUseCase.CorteCerrado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * El cierre selló el corte; cartera materializa el exigible del ciclo.
 *
 * <p>Es la vuelta del ciclo que el plan describe: el cierre propaga de regreso «cuánto se adeuda a
 * partir del corte» para que cartera no tenga que consultarlo cada día.
 */
@Component
public class CutoffClosedListener {

    private static final Logger log = LoggerFactory.getLogger(CutoffClosedListener.class);

    private final CloseCutoffCycleUseCase cierreDeCiclo;

    public CutoffClosedListener(CloseCutoffCycleUseCase cierreDeCiclo) {
        this.cierreDeCiclo = cierreDeCiclo;
    }

    @KafkaListener(
            topics = "${credit-portfolio.topics.cutoff-closed:closing.cutoff-closed}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "cutoffClosedListenerContainerFactory")
    public void onCutoffClosed(CutoffClosedPayload event) {
        log.debug("cutoff-closed recibido creditAccountId={} ciclo={} vence={}",
                event.creditAccountId(), event.cycleNumber(), event.paymentDueDate());

        cierreDeCiclo.onCutoffClosed(new CorteCerrado(
                event.creditAccountId(), event.cycleNumber(), event.cutoffDate(),
                event.paymentDueDate(), event.balanceAtCutoff()));
    }
}
