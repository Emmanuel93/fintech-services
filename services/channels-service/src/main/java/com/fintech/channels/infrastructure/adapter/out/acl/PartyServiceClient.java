package com.fintech.channels.infrastructure.adapter.out.acl;

import com.fintech.channels.application.ChannelsProperties;
import com.fintech.channels.application.port.out.PartyStatusChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.UUID;

@Component
class PartyServiceClient implements PartyStatusChecker {

    private static final Logger log = LoggerFactory.getLogger(PartyServiceClient.class);

    private final RestClient restClient;

    PartyServiceClient(ChannelsProperties properties) {
        this.restClient = RestClient.builder()
                .baseUrl(properties.getPartyServiceUrl())
                .build();
    }

    @Override
    public PartyStatus check(UUID partyId, String requestingUserId) {
        try {
            PartyResponse response = restClient.get()
                    .uri("/api/v1/parties/{id}", partyId)
                    .header("X-User-Id", requestingUserId)
                    .retrieve()
                    .body(PartyResponse.class);

            if (response == null) {
                log.warn("Null response from party-service for partyId={}", partyId);
                return new PartyStatus("ACTIVE", false);
            }
            return new PartyStatus(response.status(), false);
        } catch (RestClientException ex) {
            log.warn("party-service unreachable for partyId={}: {} — assuming ACTIVE", partyId, ex.getMessage());
            return new PartyStatus("ACTIVE", false);
        }
    }

    private record PartyResponse(
            UUID partyId,
            String partyType,
            String status,
            String firstName,
            String lastName1,
            Instant createdAt
    ) {}
}
