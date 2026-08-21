package com.fintech.beneficiary.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * El Party de la beneficiaria y su relación con el distribuidor.
 *
 * <p>El Party lo crea party-service solo, al consumir {@code origination.prospect-created}: aquí
 * sólo se espera a que exista y se registra la relación comercial, que es lo que convierte a una
 * desconocida en «su clienta desde 2023» en el directorio.
 */
@Component
public class PartyClient {

    private static final Logger log = LoggerFactory.getLogger(PartyClient.class);

    private final WebClient webClient;

    public PartyClient(@Qualifier("partyWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /** El Party creado a partir de un prospecto. Vacío mientras el evento no se haya consumido. */
    public Optional<PartyResponse> findByProspect(UUID prospectId) {
        try {
            return Optional.ofNullable(webClient.get()
                    .uri("/api/v1/parties/by-prospect/{p}", prospectId)
                    .retrieve()
                    .bodyToMono(PartyResponse.class)
                    .block());
        } catch (RuntimeException e) {
            log.debug("party aún no existe para prospecto {}: {}", prospectId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Espera a que el Party exista. La creación es asíncrona por Kafka, así que un KYC que cierra
     * en el mismo segundo llegaría antes que el evento.
     */
    public Optional<PartyResponse> awaitByProspect(UUID prospectId, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            Optional<PartyResponse> found = findByProspect(prospectId);
            if (found.isPresent()) return found;
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Registra que esta persona es beneficiaria de este distribuidor.
     *
     * <p>No es crítica para el dinero: si falla, la colocación sigue siendo válida y lo que se
     * pierde es una fila del directorio. Por eso se traga el error en vez de tumbar el KYC
     * completo, que es lo caro de repetir.
     */
    public void linkBeneficiary(UUID distributorPartyId, UUID beneficiaryPartyId) {
        try {
            // El id que trae la colocación es el del **prospecto** del distribuidor —el `sub` de
            // su JWT—, y party guarda su Party con un id propio. Sin traducirlo, la relación se
            // pedía sobre un id que party no conoce y respondía 404.
            UUID ownerPartyId = findByProspect(distributorPartyId)
                    .map(PartyResponse::partyId)
                    .orElse(distributorPartyId);
            webClient.post()
                    .uri("/api/v1/parties/{id}/relationships", ownerPartyId)
                    .bodyValue(Map.of(
                            "relatedPartyId", beneficiaryPartyId.toString(),
                            "relationshipType", "BENEFICIARY",
                            "status", "ACTIVE"))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("relación distribuidor {} -> beneficiaria {} registrada",
                    distributorPartyId, beneficiaryPartyId);
        } catch (RuntimeException e) {
            log.warn("no se pudo registrar la relación {} -> {}: {}",
                    distributorPartyId, beneficiaryPartyId, e.getMessage());
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record PartyResponse(UUID partyId, UUID prospectId, String firstName,
                                String lastName1, String lastName2, String status) {
        public String fullName() {
            return (nullToEmpty(firstName) + " " + nullToEmpty(lastName1) + " " + nullToEmpty(lastName2)).trim();
        }
        private static String nullToEmpty(String s) { return s == null ? "" : s; }
    }
}
