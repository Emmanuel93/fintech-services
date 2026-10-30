package com.fintech.beneficiary.domain;

/**
 * Las tres capturas que sólo ella puede hacer desde su teléfono.
 *
 * <p>Los nombres coinciden con {@code ProspectDocumentType} de origination a propósito: al cerrar
 * el KYC se suben allá tal cual, y una tabla de traducción entre dos enums que dicen lo mismo sería
 * una oportunidad de mandar el reverso como frente.
 */
public enum KycDocumentType {
    INE_FRONT,
    INE_BACK,
    SELFIE
}
