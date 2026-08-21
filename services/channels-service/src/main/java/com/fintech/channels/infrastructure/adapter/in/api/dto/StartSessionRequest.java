package com.fintech.channels.infrastructure.adapter.in.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload enviado por el cliente al iniciar una sesión.
 *
 * <p>Los campos de red/browser (ipAddress, ipCountry, userAgent) son ignorados si se proveen
 * desde el cliente; el servidor siempre los extrae de los headers HTTP para prevenir spoofing.
 *
 * <p>Los indicadores de seguridad (isRooted, isEmulator) son auto-reportados por el SDK del
 * cliente y se almacenan tal cual — un adversario sofisticado puede falsificarlos, por lo que
 * su ausencia (false) no garantiza dispositivo limpio.
 */
public record StartSessionRequest(
        @NotBlank String channelType,

        // ── Identidad del dispositivo ────────────────────────────────────────
        String deviceId,
        String deviceType,            // MOBILE | TABLET | DESKTOP | ATM | KIOSK | IVR
        String deviceModel,           // "iPhone 15 Pro"
        String deviceManufacturer,    // "Apple"

        // ── Sistema operativo ────────────────────────────────────────────────
        String os,
        String osVersion,             // "17.2"

        // ── Versión de aplicación ────────────────────────────────────────────
        String appVersion,            // "2.1.3"
        String sdkVersion,            // "3.0.1" (integraciones B2B/API)

        // ── Red ──────────────────────────────────────────────────────────────
        String networkType,           // WIFI | LTE | 5G | ETHERNET

        // ── Indicadores de seguridad (auto-reportados por SDK) ────────────────
        boolean isTrustedDevice,
        boolean isRooted,
        boolean isEmulator
) {}
