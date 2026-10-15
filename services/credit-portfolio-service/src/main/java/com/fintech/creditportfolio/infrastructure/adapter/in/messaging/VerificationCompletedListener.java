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
        // Sin partyId no hay a quién aplicárselo: compliance publica el primer veredicto cuando el
        // Party puede no existir todavía, y republica en cuanto lo conoce. Ignorarlo aquí no pierde
        // nada y deja a la persona sin veredicto — que bloquea, o sea el lado seguro.
        if (event == null || event.partyId() == null) return;

        verifications.findByPartyId(event.partyId())
                .ifPresentOrElse(
                        v -> v.apply(event.identityStatus(), event.screeningStatus(),
                                Boolean.TRUE.equals(event.allowsToProceed()), event.blockReason()),
                        () -> verifications.save(PartyVerification.of(
                                event.partyId(), event.identityStatus(), event.screeningStatus(),
                                Boolean.TRUE.equals(event.allowsToProceed()), event.blockReason())));

        log.info("Veredicto de {} → identidad={} listas={}",
                event.partyId(), event.identityStatus(), event.screeningStatus());
    }
}
