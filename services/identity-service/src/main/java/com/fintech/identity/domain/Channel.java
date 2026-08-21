package com.fintech.identity.domain;

/**
 * Canal por el que se emitió un token. Viaja como claim {@code channel} en el JWT y el gateway lo
 * exige por subdominio: {@code mobile.*} solo acepta MOBILE, {@code backoffice.*} solo acepta
 * BACKOFFICE. Sin esta separación un token emitido para la app móvil abriría el backoffice.
 */
public enum Channel {

    /** App móvil del cliente — el sujeto del token es un {@code partyId}. */
    MOBILE,

    /** Consola operativa/administrativa — el sujeto del token es un {@code staffUserId}. */
    BACKOFFICE,

    /** Cliente OAuth máquina-a-máquina — el sujeto del token es un {@code clientId}. */
    SERVICE;

    /**
     * Los tokens firmados antes de que existiera el claim no lo traen; se interpretan como MOBILE,
     * que era el único canal posible cuando se emitieron.
     */
    public static Channel fromClaim(String raw) {
        if (raw == null || raw.isBlank()) {
            return MOBILE;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return MOBILE;
        }
    }
}
