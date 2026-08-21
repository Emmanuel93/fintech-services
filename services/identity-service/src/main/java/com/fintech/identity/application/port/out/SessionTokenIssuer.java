package com.fintech.identity.application.port.out;

import com.fintech.identity.application.IssuedSession;

import java.util.List;
import java.util.UUID;

/**
 * Puerto de salida para emitir pares de tokens de sesión (access + refresh).
 * Desacopla AuthService, MfaService y StaffAuthService de la lógica concreta de generación de tokens.
 */
public interface SessionTokenIssuer {

    /** Sesión de cliente (canal MOBILE); los roles son siempre {@code CUSTOMER}. */
    IssuedSession issue(UUID partyId, String deviceId, String ipAddress, String userAgent);

    /**
     * Sesión de empleado (canal BACKOFFICE). Los roles se pasan explícitamente porque son los del
     * {@code StaffUser} y cambian con su administración — no son un valor fijo como en el móvil.
     */
    IssuedSession issueForStaff(UUID staffUserId, List<String> roles, String ipAddress, String userAgent);
}
