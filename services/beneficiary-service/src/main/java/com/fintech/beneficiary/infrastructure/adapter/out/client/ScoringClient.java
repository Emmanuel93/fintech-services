package com.fintech.beneficiary.infrastructure.adapter.out.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;
import java.util.UUID;

/**
 * El historial de la beneficiaria en las sociedades de información crediticia.
 *
 * <p>Se lee con la autorización que firmó <b>ella</b> —la que viaja en su prospecto— y nunca con
 * la del distribuidor. Aquí sólo se recupera lo ya consultado: quien dispara la consulta es el
 * prefetch de scoring cuando nace el prospecto con el consentimiento marcado.
 *
 * <p>Se devuelve el JSON crudo en vez de mapearlo a un record: el reporte del buró tiene decenas
 * de campos que este servicio no usa, y fijarlos en una clase obligaría a tocar código cada vez
 * que scoring agregue uno.
 */
@Component
public class ScoringClient {

    private static final Logger log = LoggerFactory.getLogger(ScoringClient.class);

    private final WebClient webClient;

    public ScoringClient(@Qualifier("scoringWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** El reporte de círculo del prospecto, si ya se consultó. */
    public Optional<JsonNode> reportOf(UUID prospectId) {
        return get("/api/v1/scoring/reports/by-prospect/{p}", prospectId);
    }

    /**
     * La evaluación más reciente. Se lee para tener el score, <b>no para decidir</b>: la decisión
     * de scoring (aprobar, comité, rechazar) se ignora a propósito, porque en la colocación quien
     * decide es el distribuidor y Kredius no le filtra por historial.
     */
    public Optional<JsonNode> latestEvaluation(UUID prospectId) {
        return get("/api/v1/scoring/evaluations/{p}/latest", prospectId);
    }

    private Optional<JsonNode> get(String path, UUID prospectId) {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri(path, prospectId)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block());
        } catch (RuntimeException e) {
            // Que el buró no haya respondido todavía es un estado normal del flujo, no una falla:
            // el llamador lo traduce a «sigue en curso» y la app enseña la espera.
            log.info("scoring sin respuesta para prospecto {} ({}): {}", prospectId, path, e.getMessage());
            return Optional.empty();
        }
    }
}
