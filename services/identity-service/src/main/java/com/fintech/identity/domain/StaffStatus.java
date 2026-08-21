package com.fintech.identity.domain;

/**
 * Estado de la cuenta de un empleado. {@code LOCKED} es transitorio y lo pone el propio sistema tras
 * demasiados intentos fallidos; los demás los administra un ADMIN.
 */
public enum StaffStatus {

    /** Puede iniciar sesión. */
    ACTIVE,

    /** Suspendido temporalmente por un administrador — no puede iniciar sesión. */
    SUSPENDED,

    /** Bloqueado por intentos fallidos; se libera solo al vencer {@code lockedUntil}. */
    LOCKED,

    /** Baja definitiva. No se borra el registro: la bitácora y las comisiones lo referencian. */
    DISABLED
}
