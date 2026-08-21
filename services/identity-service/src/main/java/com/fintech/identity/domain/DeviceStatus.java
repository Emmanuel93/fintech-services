package com.fintech.identity.domain;

public enum DeviceStatus {
    /** Dispositivo activo — puede autenticar. */
    ACTIVE,
    /** Dispositivo bloqueado manualmente o por política de fraude — no puede autenticar. */
    BLOCKED
}
