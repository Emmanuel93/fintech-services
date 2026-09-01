package com.fintech.risk.infrastructure.adapter.in.messaging;

import com.fintech.risk.application.service.RiskProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * BK-35 · un apoyo por contingencia marca forborne, igual que cualquier reestructura.
 *
 * <p>Reutiliza {@code onRestructureExecuted}, y es deliberado: un programa de apoyo <b>es</b> una
 * reestructura, y tener dos caminos que marcan lo mismo garantizaría que uno de los dos se quede
 * atrás cuando cambie la regla de cura.
 *
 * <p><b>La reserva sube</b> por el paso a STAGE_2. Es el costo asumido del apoyo: a cambio, el
 * historial del cliente ante el buró no se degrada — {@code collections} sólo reporta
 * {@code WRITE_OFF} y {@code QUITA_PARCIAL}.
 */
@Component
public class ReliefGrantedListener {

    private static final Logger log = LoggerFactory.getLogger(ReliefGrantedListener.class);

    private final RiskProfileService riskProfileService;

    public ReliefGrantedListener(RiskProfileService riskProfileService) {
        this.riskProfileService = riskProfileService;
    }

    @KafkaListener(
            topics = "credit-portfolio.relief-granted",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "reliefGrantedListenerContainerFactory")
    public void onReliefGranted(ReliefGrantedPayload event) {
        log.info("Apoyo otorgado cuenta={} programa='{}' motivo={} períodos={} — se marca forborne "
                        + "(piso STAGE_2, la reserva sube)",
                event.creditAccountId(), event.programName(), event.reason(),
                event.deferredPeriods());

        riskProfileService.onRestructureExecuted(event.creditAccountId());
    }
}
