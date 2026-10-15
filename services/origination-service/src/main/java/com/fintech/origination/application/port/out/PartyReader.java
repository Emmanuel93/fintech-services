package com.fintech.origination.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface PartyReader {

    Optional<PartyData> findByPartyId(UUID partyId);

    /**
     * El veredicto de verificación de la persona detrás del prospecto.
     *
     * <p><b>Nunca devuelve vacío.</b> Si party no contesta, devuelve un veredicto que <b>bloquea</b>
     * — porque no haber podido comprobar no es haber comprobado. Un {@code Optional} invitaría a
     * que el llamador escribiera {@code .map(...).orElse(true)}, que es fallar abierto escrito de
     * una forma que parece cuidadosa.
     */
    Verification verificationOf(UUID prospectId);

    record PartyData(UUID prospectId, String partyType) {}

    /**
     * @param allowsToProceed las DOS cosas: identidad verificada y listas despejadas
     * @param blockReason     por qué no pasa, para que el mensaje al cliente pueda ser el correcto:
     *                        a un KYC en revisión se le pide mejor evidencia; a una coincidencia de
     *                        listas, nada — no hay nada que la persona pueda hacer
     */
    record Verification(String identityStatus, String screeningStatus,
                        boolean allowsToProceed, String blockReason) {

        /** Party no contestó. Bloquea, y lo dice. */
        public static Verification unavailable(String detail) {
            return new Verification("UNKNOWN", "UNKNOWN", false,
                    "no se pudo comprobar la verificación de la persona: " + detail);
        }
    }
}
