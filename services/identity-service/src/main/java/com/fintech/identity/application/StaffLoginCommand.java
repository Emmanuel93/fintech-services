package com.fintech.identity.application;

/**
 * Comando de inicio de sesión de un empleado. No lleva {@code deviceId}: el backoffice es una
 * consola web, no una app instalada, y el tracking de dispositivo no aplica.
 */
public record StaffLoginCommand(

        String email,
        String password,

        /** IP del cliente; considera X-Forwarded-For. */
        String ipAddress,

        /** User-Agent del navegador. */
        String userAgent,

        /** X-Request-ID para correlación distribuida. */
        String requestId
) {}
