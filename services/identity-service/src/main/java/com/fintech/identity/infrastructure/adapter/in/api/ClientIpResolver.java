package com.fintech.identity.infrastructure.adapter.in.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Utilidad para resolver la IP real del cliente considerando proxies y balanceadores de carga.
 * Prioriza: X-Forwarded-For → X-Real-IP → RemoteAddr.
 */
final class ClientIpResolver {

    private ClientIpResolver() {}

    static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            // X-Forwarded-For puede ser lista: "client, proxy1, proxy2"
            return forwarded.split(",")[0].strip();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.strip();
        }
        return request.getRemoteAddr();
    }
}
