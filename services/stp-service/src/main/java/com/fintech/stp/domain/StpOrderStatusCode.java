package com.fintech.stp.domain;

import java.util.Map;
import java.util.Optional;

/**
 * Estados que STP reporta en la conciliación, y su traducción al resultado de negocio.
 *
 * <p>Fuente de verdad ÚNICA de este mapeo. El legado tenía dos: una que resolvía por nombre desde
 * el webhook de estado y otra que resolvía por código desde la conciliación, con un
 * {@code default -> null} que dejaba transacciones sin estado en silencio.
 */
public enum StpOrderStatusCode {

    LIQUIDADA,
    DEVUELTA,
    CANCELADA;

    private static final Map<String, StpOrderStatusCode> BY_CODE = Map.ofEntries(
            // Liquidadas
            Map.entry("LQ", LIQUIDADA),
            Map.entry("TLQ", LIQUIDADA),
            Map.entry("CCO", LIQUIDADA),
            Map.entry("CXO", LIQUIDADA),
            Map.entry("CCE", LIQUIDADA),
            // Devueltas
            Map.entry("D", DEVUELTA),
            Map.entry("TD", DEVUELTA),
            Map.entry("RE", DEVUELTA),
            // Canceladas
            Map.entry("CL", CANCELADA),
            Map.entry("TCL", CANCELADA));

    /**
     * Traduce un código de STP. Devuelve vacío para códigos no catalogados o para órdenes que
     * siguen en tránsito — el llamador debe dejarlas en vuelo, no adivinar.
     */
    public static Optional<StpOrderStatusCode> fromStpCode(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(rawCode.trim().toUpperCase()));
    }

    /** True si el código indica un desenlace definitivo (ya no hay que seguir consultando). */
    public static boolean isTerminal(String rawCode) {
        return fromStpCode(rawCode).isPresent();
    }
}
