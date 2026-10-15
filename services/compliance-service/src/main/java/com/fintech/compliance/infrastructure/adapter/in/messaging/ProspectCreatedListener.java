package com.fintech.compliance.infrastructure.adapter.in.messaging;

import com.fintech.compliance.application.service.PersonVerificationService;
import com.fintech.compliance.domain.ScreeningSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * «Se registró una persona» → se le corre KYC y AML.
 *
 * <p>Es el mismo evento del que ya cuelga el prefetch de buró en `scoring`, y por el que pasan los
 * tres caminos de alta. Colgar aquí la verificación es lo que hace que sea <b>un solo proceso</b>
 * para todos los registrados y no tres procesos paralelos que se desincronizan.
 *
 * <p>El país se fija en {@code MX} porque el alta es mexicana y el prospecto no lo trae. Es un dato
 * para <b>discriminar</b> candidatos —«el de la lista es sirio»— no para decidir: equivocarlo no
 * deja pasar a nadie, sólo hace que un candidato extranjero no se descarte solo.
 */
@Component
public class ProspectCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ProspectCreatedListener.class);

    private final PersonVerificationService verifications;

    public ProspectCreatedListener(PersonVerificationService verifications) {
        this.verifications = verifications;
    }

    @KafkaListener(
            topics = "origination.prospect-created",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "prospectCreatedListenerContainerFactory")
    public void onProspectCreated(ProspectCreatedPayload event) {
        if (event == null || event.prospectId() == null) return;

        String nombre = event.fullName();
        if (nombre.isBlank()) {
            // Sin nombre no hay contra qué cruzar. Se registra la verificación igual —en PENDING,
            // que bloquea— en vez de saltarla: saltarla dejaría a la persona sin veredicto, y sin
            // veredicto la compuerta no sabría que falta comprobarla.
            log.warn("El prospecto {} llegó sin nombre — se registra la verificación en PENDING",
                    event.prospectId());
        }

        verifications.evaluateNewPerson(new ScreeningSubject(
                null, nombre, event.dateOfBirth(), event.curp(), event.rfc(), "MX"),
                event.prospectId());
    }
}
