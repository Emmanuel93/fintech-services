package com.fintech.compliance.infrastructure.adapter.out.party;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fintech.compliance.application.port.out.PartyDirectoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * Pregunta a party qué Party le tocó a un prospecto.
 *
 * <p>Timeouts cortos y sin reintento: se llama al publicar un veredicto, y un party lento no puede
 * convertirse en un dictamen que no se guarda. Si no contesta, el veredicto sale como antes —sin
 * partyId— y la siguiente publicación lo vuelve a intentar.
 */
@Component
class RestPartyDirectoryAdapter implements PartyDirectoryPort {

    private static final Logger log = LoggerFactory.getLogger(RestPartyDirectoryAdapter.class);

    private final RestClient client;

    RestPartyDirectoryAdapter(@Value("${fintech.compliance.party-service-url:http://localhost:8083}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<UUID> partyIdOf(UUID prospectId) {
        try {
            PartyRef ref = client.get()
                    .uri("/api/v1/parties/by-prospect/{id}", prospectId)
                    // party exige identidad de quien pregunta; compliance pregunta en su nombre.
                    .header("X-User-Id", "compliance-service")
                    .retrieve()
                    .body(PartyRef.class);
            return Optional.ofNullable(ref).map(PartyRef::partyId);
        } catch (RuntimeException e) {
            // 404 es lo normal en el primer segundo de vida de una persona; cualquier otra cosa se
            // registra, pero tampoco detiene la publicación.
            log.debug("party no resolvió el prospecto {}: {}", prospectId, e.getMessage());
            return Optional.empty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PartyRef(UUID partyId) {}
}
