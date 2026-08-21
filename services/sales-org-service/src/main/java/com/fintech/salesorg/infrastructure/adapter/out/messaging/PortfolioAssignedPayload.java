package com.fintech.salesorg.infrastructure.adapter.out.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound {@code sales-org.portfolio-assigned}.
 *
 * <p>Lleva las dos decisiones juntas porque se toman juntas y tienen dueños distintos: la
 * **sucursal** la consume credit-portfolio para sellarla —inmutable, es el eje contable— y el
 * **ejecutivo** lo consume party, y ése sí rota cuantas veces haga falta.
 *
 * <p>{@code nivelEscalado} dice a cuántos niveles del árbol hubo que subir para encontrar a alguien:
 * 0 es lo normal —la propia sucursal—, y cualquier cosa por encima es una plaza sin plantilla que
 * conviene ver antes de que se convierta en el nacional cargando con media red.
 */
public record PortfolioAssignedPayload(
        String eventId,
        UUID creditAccountId,
        UUID obligorPartyId,
        UUID executiveStaffId,
        UUID unitId,
        String unitCode,
        int nivelEscalado,
        Instant occurredOn
) {}
