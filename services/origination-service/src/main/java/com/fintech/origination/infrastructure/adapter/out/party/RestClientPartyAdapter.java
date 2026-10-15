package com.fintech.origination.infrastructure.adapter.out.party;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.origination.application.port.out.PartyReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.UUID;

@Component
public class RestClientPartyAdapter implements PartyReader {

    private static final Logger log = LoggerFactory.getLogger(RestClientPartyAdapter.class);

    private final RestClient partyRestClient;

    public RestClientPartyAdapter(@Qualifier("partyRestClient") RestClient partyRestClient) {
        this.partyRestClient = partyRestClient;
    }

    @Override
    public Optional<PartyData> findByPartyId(UUID partyId) {
        try {
            PartyApiResponse response = partyRestClient
                    .get()
                    .uri("/api/v1/parties/{id}", partyId)
                    .retrieve()
                    .body(PartyApiResponse.class);

            if (response == null || response.prospectId() == null) {
                log.warn("party-service returned empty response for partyId={}", partyId);
                return Optional.empty();
            }
            return Optional.of(new PartyData(response.prospectId(), response.partyType()));
        } catch (RestClientException e) {
            log.error("party-service unavailable resolving partyId={}: {}", partyId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Verification verificationOf(UUID prospectId) {
        try {
            PartyApiResponse response = partyRestClient
                    .get()
                    .uri("/api/v1/parties/by-prospect/{id}", prospectId)
                    .retrieve()
                    .body(PartyApiResponse.class);

            if (response == null) {
                // No es lo mismo que «no existe»: es que la respuesta no se pudo leer. Bloquea.
                return Verification.unavailable("party devolvió una respuesta vacía");
            }
            return new Verification(
                    response.identityStatus(), response.screeningStatus(),
                    response.verificationClear(), response.verificationBlockReason());

        } catch (RestClientException e) {
            // **Falla cerrado.** La indisponibilidad de una comprobación regulatoria tiene que
            // detener, no dejar pasar: lo contrario significa originar crédito a alguien sin haber
            // comprobado que no está en una lista, porque un servicio estaba caído.
            log.error("No se pudo comprobar la verificación de prospectId={} — NO se origina: {}",
                    prospectId, e.getMessage());
            return Verification.unavailable(e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PartyApiResponse(UUID prospectId, String partyType,
                                    String identityStatus, String screeningStatus,
                                    boolean verificationClear, String verificationBlockReason) {}
}
