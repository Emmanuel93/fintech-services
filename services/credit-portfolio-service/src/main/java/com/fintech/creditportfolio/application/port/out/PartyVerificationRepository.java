package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.PartyVerification;

import java.util.Optional;
import java.util.UUID;

public interface PartyVerificationRepository {

    /**
     * El veredicto de una persona por <b>el id con el que la cartera la conoce</b>.
     *
     * <p>El nombre se conserva por quienes ya lo llaman, pero el id puede ser de dos espacios: el
     * responsable de una cuenta se guarda por su prospectId ({@code obligor_party_id}) y la
     * beneficiaria de una colocación llega por su partyId. La implementación busca en los dos, para
     * que la compuerta no tenga que saber cuál de los dos le tocó.
     */
    Optional<PartyVerification> findByPartyId(UUID personId);

    /** Para el listener: el veredicto siempre trae prospecto, y es la llave estable. */
    Optional<PartyVerification> findByProspectId(UUID prospectId);

    PartyVerification save(PartyVerification verification);
}
