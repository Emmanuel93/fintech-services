package com.fintech.beneficiary.domain;

/**
 * Los tres consentimientos del paso 6 del KYC web.
 *
 * <p>Son tres y no uno porque responden a tres preguntas legales distintas y cada una puede tener
 * su propio texto versionado: el aviso de privacidad (LFPDPPP), la autorización para consultar su
 * historial en las SIC, y la declaración de que el crédito es suyo frente a la distribuidora. Uno
 * solo con tres casillas perdería cuál de los tres textos estaba vigente cuando aceptó cada cosa.
 */
public enum ConsentType {
    PRIVACY_NOTICE,
    BUREAU_AUTHORIZATION,
    CREDIT_OWNERSHIP
}
