package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.port.out.PartyVerificationRepository;
import com.fintech.creditportfolio.domain.PartyVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantiene al día el veredicto de cada persona, para poder frenar una disposición sin preguntar.
 *
 * <p>Va por evento y no por llamada porque la comprobación está <b>en el camino del dinero</b>. Una
 * llamada síncrona añadiría una dependencia que puede no contestar en el peor momento, y fallar
 * cerrado ahí —lo correcto— significaría que un servicio caído detiene todos los desembolsos. Con
 * proyección, comprobar cuesta una lectura local y el veredicto vigente siempre está aquí.
 *
 * <p>Compliance republica en cada cambio —y también cuando se activa una lista nueva y vuelve a
 * cruzar a la población viva—, así que la proyección refleja las listas vigentes sin que este
 * servicio tenga que saber nada de listas.
 */
@Component
public class VerificationCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(VerificationCompletedListener.class);

    private final PartyVerificationRepository verifications;

    public VerificationCompletedListener(PartyVerificationRepository verifications) {
        this.verifications = verifications;
    }

    @KafkaListener(
            topics = "compliance.verification-completed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "verificationCompletedListenerContainerFactory")
    @Transactional
    public void onVerificationCompleted(VerificationCompletedPayload event) {
        if (event == null || (event.partyId() == null && event.prospectId() == null)) return;

        // Por prospecto primero: es la llave que trae todo veredicto y la que guarda la cuenta como
        // responsable. Antes se descartaba todo lo que llegara sin partyId —que en el alta es casi
        // todo, porque el Party nace del mismo evento— y la proyección quedaba vacía: ninguna
        // disposición pasaba la compuerta, ni siquiera la de alguien comprobado y limpio.
        java.util.Optional<PartyVerification> existing = event.prospectId() != null
                ? verifications.findByProspectId(event.prospectId())
                : java.util.Optional.empty();
        if (existing.isEmpty() && event.partyId() != null) {
            existing = verifications.findByPartyId(event.partyId());
        }

        existing.ifPresentOrElse(
                v -> {
                    v.attachParty(event.partyId());
                    v.apply(event.identityStatus(), event.screeningStatus(),
                            Boolean.TRUE.equals(event.allowsToProceed()), event.blockReason());
                    verifications.save(v);
                },
                () -> verifications.save(PartyVerification.forPerson(
                        event.partyId(), event.prospectId(), event.identityStatus(),
                        event.screeningStatus(), Boolean.TRUE.equals(event.allowsToProceed()),
                        event.blockReason(), null, null)));

        log.info("Veredicto de prospecto={} party={} → identidad={} listas={}",
                event.prospectId(), event.partyId(), event.identityStatus(), event.screeningStatus());
    }
}
