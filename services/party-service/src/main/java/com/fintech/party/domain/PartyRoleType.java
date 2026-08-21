package com.fintech.party.domain;

/**
 * Roles adicionales que un party puede tener además de su {@link PartyType} (I-03). Son capacidades
 * aditivas, no tipos: un party sigue siendo INDIVIDUAL o BUSINESS y puede ganar/perder estos roles.
 */
public enum PartyRoleType {
    /** Coloca crédito B2B2C: sus beneficiarios reciben el crédito a través de él. */
    DISTRIBUTOR,
    /** Avala el crédito de otro party. */
    GUARANTOR,
    /** Recibe el beneficio de un crédito colocado por un distribuidor. */
    BENEFICIARY
}
