package com.fintech.beneficiary.infrastructure.adapter.in.api.dto;

import java.util.List;

/**
 * El historial de la beneficiaria tal como lo pinta la app.
 *
 * <p>No trae veredicto. Scoring sí calcula uno —aprobar, comité, rechazar— y aquí se ignora a
 * propósito: en la colocación quien decide es el distribuidor, y devolverle una decisión de
 * Kredius junto al reporte convertiría la pantalla en un trámite en vez de en una decisión suya.
 *
 * <p>{@code title} y {@code description} los redacta el servidor porque dependen de datos que la
 * app no tiene: sin ellos, el teléfono tendría que interpretar un score y escribir la frase, que
 * es exactamente el tipo de regla que no debe vivir en el cliente.
 */
public record BureauReportResponse(
        int score,
        String band,
        String bandLabel,
        String title,
        String description,
        List<Fact> facts) {

    /** Un renglón del desglose. {@code tone} sólo gobierna el color: ok, warn o bad. */
    public record Fact(String label, String value, String tone) {}
}
