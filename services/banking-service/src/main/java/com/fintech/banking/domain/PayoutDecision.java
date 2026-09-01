package com.fintech.banking.domain;

import java.util.UUID;

/**
 * La respuesta completa a «¿por dónde sale este pago?»: <b>cuenta, rail y proveedor</b>, en un solo
 * objeto.
 *
 * <p>Que lleve los datos de la cuenta ordenante y no sólo su id es deliberado. El conector necesita
 * CLABE, titular, RFC y su número de cliente para armar la cadena original; si sólo recibiera un id
 * tendría que resolverlo contra su propia copia del catálogo — que es de dónde venimos.
 *
 * <p>La CLABE viaja completa <b>una sola vez y hacia adentro</b>: al conector, que la necesita.
 * Nunca sale por una API de consulta ni entra a una bitácora.
 */
public record PayoutDecision(UUID routeId,
                             UUID bankAccountId,
                             String orderingClabe,
                             String orderingHolderName,
                             String orderingTaxId,
                             String providerClientRef,
                             String institutionCode,
                             PayoutRail rail,
                             PayoutProvider provider) {

    public static PayoutDecision de(PayoutRoute ruta, BankAccount cuenta) {
        return new PayoutDecision(ruta.getId(), cuenta.getId(), cuenta.getClabe(),
                cuenta.getHolderName(), cuenta.getTaxId(), cuenta.getProviderClientRef(),
                cuenta.getInstitutionCode(), ruta.railValue(), ruta.providerValue());
    }

    /** Para bitácora: la decisión sin el número de cuenta. */
    @Override
    public String toString() {
        return "PayoutDecision[ruta=" + routeId + " cuenta=" + bankAccountId
                + " clabe=****" + orderingClabe.substring(14)
                + " rail=" + rail + " proveedor=" + provider + "]";
    }
}
