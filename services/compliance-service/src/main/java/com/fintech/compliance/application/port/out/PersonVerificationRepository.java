package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.PersonVerification;

import java.util.Optional;
import java.util.UUID;

public interface PersonVerificationRepository {

    /** Idempotencia del consumo: el mismo prospecto no se evalúa dos veces. */
    Optional<PersonVerification> findByProspectId(UUID prospectId);

    Optional<PersonVerification> findByPartyId(UUID partyId);

    Optional<PersonVerification> findById(UUID verificationId);

    PersonVerification save(PersonVerification verification);

    /**
     * La población viva, por páginas.
     *
     * <p>Por páginas y no entera: un re-cruce mira a todos los que tienen veredicto, y traerlos en
     * una lista obligaría a tener la cartera completa en memoria. Con páginas, una corrida
     * interrumpida deja actualizado lo que alcanzó en vez de perderlo todo.
     */
    java.util.List<PersonVerification> findPage(int page, int size);
}
