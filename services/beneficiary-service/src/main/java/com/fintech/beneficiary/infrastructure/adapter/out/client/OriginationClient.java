package com.fintech.beneficiary.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * El expediente de la beneficiaria.
 *
 * <p>El prospecto nace <b>al cerrar el KYC</b>, no al invitar. Crearlo antes llenaría origination
 * de fantasmas y —lo grave— dispararía el prefetch de buró de scoring, que se engancha a
 * {@code origination.prospect-created}, antes de que ella haya autorizado nada.
 *
 * <p>Por eso {@code circuloConsentAccepted} viaja en {@code true}: para cuando este servicio llama,
 * ella ya firmó su autorización con su propio código. Ese flag es lo que hace legal la consulta.
 */
@Component
public class OriginationClient {

    private static final Logger log = LoggerFactory.getLogger(OriginationClient.class);

    private final WebClient webClient;

    public OriginationClient(@Qualifier("originationWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public ProspectResponse createIndividualProspect(NewProspect p) {
        Map<String, Object> body = new HashMap<>();
        body.put("prospectType", "INDIVIDUAL");
        body.put("firstName", p.firstName());
        body.put("lastName1", p.lastName1());
        body.put("lastName2", p.lastName2());
        body.put("curp", p.curp());
        if (p.rfc() != null) body.put("rfc", p.rfc());
        body.put("dateOfBirth", p.dateOfBirth());
        body.put("gender", p.gender());
        body.put("stateOfBirth", p.stateOfBirth());
        body.put("phone", "+52" + p.phone());
        if (p.email() != null) body.put("email", p.email());
        body.put("street", p.street());
        body.put("exteriorNumber", p.exteriorNumber());
        body.put("neighborhood", p.neighborhood());
        body.put("municipality", p.municipality());
        body.put("city", p.city());
        body.put("state", p.state());
        body.put("postalCode", p.postalCode());
        body.put("country", "MX");
        // El canal es el suyo, no el del distribuidor: a ella se le llega por WhatsApp y se
        // verifica desde su propio teléfono. Es el valor que deja la trazabilidad correcta de
        // por dónde entró esta persona al sistema.
        body.put("channelType", "WHATSAPP");
        body.put("privacyNoticeAccepted", true);
        body.put("circuloConsentAccepted", true);
        body.put("username", p.phone());
        body.put("password", p.password());

        log.info("-> POST origination /prospects curp={} (beneficiaria)", p.curp());
        return webClient.post()
                .uri("/api/v1/origination/prospects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(detail -> Mono.error(new ResponseStatusException(
                                resp.statusCode(), "origination rechazó el prospecto: " + detail))))
                .bodyToMono(ProspectResponse.class)
                .block();
    }

    public record NewProspect(
            String firstName, String lastName1, String lastName2,
            String curp, String rfc, String dateOfBirth, String gender, String stateOfBirth,
            String phone, String email,
            String street, String exteriorNumber, String neighborhood,
            String municipality, String city, String state, String postalCode,
            String password) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record ProspectResponse(UUID prospectId, String curp, String phone) {}
}
