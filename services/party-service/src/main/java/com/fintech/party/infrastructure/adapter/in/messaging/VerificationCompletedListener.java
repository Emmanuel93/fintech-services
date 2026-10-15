package com.fintech.party.infrastructure.adapter.in.messaging;

import com.fintech.party.application.service.PartyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * El veredicto de compliance llega y se guarda en la persona.
 *
 * <p>`party` no decide la verificación: la registra. Quien decide es D16, que habla con los
 * proveedores y tiene las listas. Aquí vive porque `party` es dueño de la persona y es a quien
 * consultan las compuertas — y porque un veredicto que viviera en el servicio que lo produce
 * obligaría a todos los que bloquean a llamar a un servicio más.
 *
 * <p>Se identifica por <b>prospectId</b> y no por partyId: cuando compliance emite el primer
 * veredicto, el Party puede no existir todavía —los dos cuelgan del mismo evento de alta— pero el
 * prospecto sí, y es la llave que los dos comparten.
 */
@Component
public class VerificationCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationCompletedListener.class);

    private final PartyService partyService;

    public VerificationCompletedListener(PartyService partyService) {
        this.partyService = partyService;
    }

    @KafkaListener(
            topics = "compliance.verification-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "verificationCompletedListenerContainerFactory")
    public void onVerificationCompleted(VerificationCompletedPayload event) {
        if (event == null || event.prospectId() == null) return;

        partyService.applyVerification(
                event.prospectId(), event.identityStatus(), event.screeningStatus(), event.blockReason());

        log.info("Veredicto aplicado prospectId={} identidad={} listas={}",
                event.prospectId(), event.identityStatus(), event.screeningStatus());
    }
}
