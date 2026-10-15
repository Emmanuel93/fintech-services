package com.fintech.origination.domain;

import java.util.UUID;

/**
 * No se puede originar crédito para alguien que no ha pasado KYC y listas.
 *
 * <p>Lleva el motivo porque los dos casos que bloquean se le dicen distinto al cliente: a una
 * identidad en revisión se le pide mejor evidencia y puede resolverlo; a una coincidencia de listas
 * no se le pide nada — no hay nada que la persona pueda hacer, y mandarla a repetir su selfie sería
 * enviarla a resolver algo que no cambia nada.
 */
public class PersonNotVerifiedException extends RuntimeException {

    private final UUID prospectId;
    private final String reason;

    public PersonNotVerifiedException(UUID prospectId, String reason) {
        super("La persona del prospecto " + prospectId + " no puede originar crédito: " + reason);
        this.prospectId = prospectId;
        this.reason     = reason;
    }

    public UUID prospectId() { return prospectId; }
    public String reason()   { return reason; }
}
