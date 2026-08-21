package com.fintech.party.domain;

/**
 * Cómo se relacionan dos parties. Se lee siempre en una dirección: {@code partyId} <b>tiene como</b>
 * {@code relationshipType} a {@code relatedPartyId}.
 *
 * <p>Los valores replican el CHECK de {@code party.party_relationships} (migración 006). La
 * relación se cierra, no se borra: quién avalaba o a quién se le colocó un crédito es un hecho con
 * fecha, y borrarlo dejaría créditos pasados sin explicación.
 */
public enum PartyRelationshipType {
    /** Avala el crédito del otro. */
    GUARANTOR,
    /** Recibe el crédito que el otro coloca — el vínculo distribuidora → cliente final (B2B2C). */
    BENEFICIARY,
    /** Firma por una persona moral. */
    LEGAL_REPRESENTATIVE,
    /** Coloca crédito al otro. Es el reverso de {@link #BENEFICIARY}, visto desde el cliente. */
    DISTRIBUTOR,
    /** Comparte la obligación de pago. */
    COSIGNER
}
