package com.fintech.origination.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Resuelve el {@code promoterCode} capturado por el canal al partyId del distribuidor que lo respalda.
 * Sin esto, un código que no es un UUID viajaba opaco hasta commission y el crédito nunca acreditaba
 * comisión (bomba CM-07). La resolución vive en sales-org (dueño de la matriz comercial).
 */
public interface PromoterResolver {

    /** El partyId del distribuidor con ese código, o vacío si no existe. */
    Optional<UUID> resolveDistributor(String promoterCode);
}
