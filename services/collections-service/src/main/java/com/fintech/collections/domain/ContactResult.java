package com.fintech.collections.domain;

/**
 * Cómo terminó el intento.
 *
 * <p>Los cuatro primeros describen una llamada y son los que captura un agente. Los tres últimos
 * describen el ciclo de vida de un mensaje y los escribe el sistema al confirmar entrega: un
 * WhatsApp entregado no «contestó», y forzarlo a {@code ANSWERED} haría que el reporte de gestión
 * contara como conversaciones cosas que nadie leyó.
 */
public enum ContactResult {
    // ── Llamada, capturada por el agente ─────────────────────────────────────
    ANSWERED, NO_ANSWER, WRONG_NUMBER, PROMISE_MADE,

    // ── Mensaje, confirmado por notifications ────────────────────────────────
    /** Salió y el proveedor lo dio por entregado. */
    DELIVERED,
    /** Además hay acuse de lectura. Sólo algunos canales lo dan. */
    READ,
    /** No se pudo entregar: sin dato de contacto, rechazo del proveedor, opt-out. */
    FAILED;

    /** Los que produce el sistema; el resto los captura una persona. */
    public boolean isDeliveryOutcome() {
        return this == DELIVERED || this == READ || this == FAILED;
    }
}
