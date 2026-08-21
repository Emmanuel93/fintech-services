package com.fintech.identity.application;

import java.time.Instant;

/**
 * Resultado de emitir un par de tokens de sesión.
 * Usado por los servicios de autenticación para obtener el JTI y la expiración
 * necesarios en el evento de auditoría, junto con el par de tokens devuelto al cliente.
 */
public record IssuedSession(TokenPair pair, String jti, Instant expiresAt) {}
