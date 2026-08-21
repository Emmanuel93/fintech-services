package com.fintech.identity.domain.event;

public enum LoginOutcome {

    /** Credenciales válidas, tokens emitidos directamente. */
    SUCCESS,

    /** Credenciales válidas, pero el usuario tiene 2FA activo — se requiere verificar TOTP. */
    SUCCESS_MFA_REQUIRED,

    /** Credenciales válidas + código TOTP verificado; tokens emitidos con MFA completado. */
    SUCCESS_MFA_VERIFIED,

    /** Contraseña o NIP incorrectos; cuenta aún no bloqueada. */
    FAILED_CREDENTIALS,

    /** La cuenta quedó bloqueada en este intento (umbral de fallos alcanzado). */
    ACCOUNT_LOCKED,

    /** La cuenta ya estaba bloqueada antes de este intento. */
    ACCOUNT_ALREADY_LOCKED,

    /** El username no existe en el sistema. */
    CREDENTIAL_NOT_FOUND,

    /** Código TOTP inválido durante la verificación MFA. */
    FAILED_MFA_CODE
}
