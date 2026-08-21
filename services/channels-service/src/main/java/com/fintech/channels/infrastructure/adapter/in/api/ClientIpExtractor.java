package com.fintech.channels.infrastructure.adapter.in.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extrae la IP real del cliente desde las cabeceras HTTP.
 *
 * <p>Siempre se extrae del lado servidor para prevenir spoofing — el cliente nunca
 * debe poder indicar su propia IP en el body.
 *
 * <p>Cadena de prioridad (de mayor a menor confianza):
 * <ol>
 *   <li>{@code X-Forwarded-For} — primer IP de la lista (el cliente original, ya sanitizado
 *       por el gateway en el primer salto de proxy)</li>
 *   <li>{@code X-Real-IP} — inyectado por nginx/OpenResty en entornos de un solo proxy</li>
 *   <li>{@code REMOTE_ADDR} — dirección de conexión TCP (solo válida sin proxy intermediario)</li>
 * </ol>
 */
public final class ClientIpExtractor {

    private ClientIpExtractor() {}

    public static String extract(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
