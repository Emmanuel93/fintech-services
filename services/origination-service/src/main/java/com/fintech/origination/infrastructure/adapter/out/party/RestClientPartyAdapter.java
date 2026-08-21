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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PartyApiResponse(UUID prospectId, String partyType) {}
}
