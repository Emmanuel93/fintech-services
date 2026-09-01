package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OriginationClient {

    private static final Logger log = LoggerFactory.getLogger(OriginationClient.class);

    private final WebClient webClient;

    public OriginationClient(@Qualifier("originationWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Los datos que capturó el prospecto, para que la bitácora pueda decir quién es el cliente que
     * actuó y no sólo su UUID. Es el único sitio donde viven su correo y su teléfono.
     *
     * <p>Devuelve {@code null} en vez de propagar: lo llama el resolutor de identidad de la
     * bitácora, que degrada a sólo el identificador y jamás debe estorbar a la operación.
     */
    public ProspectDetail getProspect(UUID prospectId) {
        try {
            return webClient.get()
                    .uri("/api/v1/origination/prospects/{id}", prospectId)
                    .header("X-User-Id", prospectId.toString())
                    .retrieve()
                    .bodyToMono(ProspectDetail.class)
                    .block();
        } catch (RuntimeException e) {
            log.debug("no se pudo leer el prospecto {}: {}", prospectId, e.toString());
            return null;
        }
    }

    /** Sólo lo que la bitácora necesita del expediente; el resto del detalle se ignora. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    /**
     * El expediente capturado en el alta.
     *
     * <p>Trae más de lo que el alta necesitaba porque el perfil de la app lo muestra completo:
     * party-service guarda la identidad —nombre, CURP, RFC— pero el domicilio, el género y el
     * contacto verificado viven aquí, que es donde el usuario los escribió.
     */
    public record ProspectDetail(
            String firstName,
            String lastName1,
            String lastName2,
            String curp,
            String phone,
            String email,
            String gender,
            String stateOfBirth,
            // El domicilio viaja anidado, como lo expone origination: aplanarlo
            // aquí obligaría a mantener el mapeo en dos sitios.
            Address address
    ) {
        public record Address(
                String street, String exteriorNumber, String interiorNumber,
                String neighborhood, String municipality, String city,
                String state, String postalCode, String country
        ) {}
    }

    /**
     * Sube un documento del expediente.
     *
     * <p>Devuelve {@code false} en vez de propagar el fallo: el expediente se sube después de
     * crear el prospecto, y perder una foto no puede tumbar un alta que ya está hecha. El
     * documento que falte se vuelve a pedir —el dominio ya tiene ese estado, PENDING_DOCUMENTS—
     * en vez de obligar a repetir todo el registro.
     */
    public boolean uploadDocument(UUID prospectId, String documentType,
                                  String fileName, String contentType, String contentBase64) {
        log.info("-> PUT origination /prospects/{}/documents/{}/file", prospectId, documentType);
        try {
            webClient.put()
                    .uri("/api/v1/origination/prospects/{id}/documents/{type}/file", prospectId, documentType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("fileName", fileName, "contentType", contentType,
                                      "contentBase64", contentBase64))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception ex) {
            log.warn("No se pudo subir {} de {}: {}", documentType, prospectId, ex.getMessage());
            return false;
        }
    }

    public ProspectResponse registerProspect(RegisterProspectPayload payload) {
        log.info("-> POST origination-service /api/v1/origination/prospects phone={} curp={}",
                payload.phone(), payload.curp());
        Map<String, Object> body = buildBody(payload);

        return webClient.post()
                .uri("/api/v1/origination/prospects")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .onStatus(HttpStatusCode::is5xxServerError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(ProspectResponse.class)
                .doOnNext(r -> log.info("<- origination-service 201 prospectId={}", r.prospectId()))
                .block();
    }

    // ── Credit applications (underwriting) ──────────────────────────────────
    // userId viene del header X-User-Id inyectado por el gateway tras validar RS256.
    // Se reenvía como header interno a origination; origination no valida JWT.

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() {};

    public Map<String, Object> createApplication(String userId, CreateApplicationPayload payload) {
        log.info("-> POST origination-service /api/v1/origination/applications prospectId={} productType={} promoterCode={} userId={}",
                payload.prospectId(), payload.productType(), payload.promoterCode(), userId);
        Map<String, Object> body = new HashMap<>();
        body.put("prospectId", payload.prospectId());
        body.put("productType", payload.productType());
        if (payload.requestedAmount() != null) body.put("requestedAmount", payload.requestedAmount());
        if (payload.requestedTerm() != null) body.put("requestedTerm", payload.requestedTerm());
        if (payload.promoterCode() != null && !payload.promoterCode().isBlank()) {
            body.put("promoterCode", payload.promoterCode());
        }

        return webClient.post()
                .uri("/api/v1/origination/applications")
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(JSON_MAP)
                .doOnNext(r -> log.info("<- origination-service application status={}", r.get("status")))
                .block();
    }

    public Map<String, Object> getApplication(String userId, String applicationId) {
        log.info("-> GET origination-service /api/v1/origination/applications/{}", applicationId);
        return webClient.get()
                .uri("/api/v1/origination/applications/{id}", applicationId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(JSON_MAP)
                .block();
    }

    /**
     * Las solicitudes de un prospecto, ya desenvueltas de la página de origination.
     *
     * <p>El llamador quiere la lista; que la ruta esté paginada es un detalle del transporte y se
     * resuelve aquí, no en el controller.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<Map<String, Object>> listApplications(String userId, String prospectId) {
        log.info("-> GET origination-service /api/v1/origination/applications?prospectId={}", prospectId);
        Map<String, Object> pagina = fetchApplicationsPage(userId, prospectId);
        if (pagina == null) return java.util.List.of();
        Object contenido = pagina.get("content");
        return contenido instanceof java.util.List<?> lista
                ? (java.util.List<Map<String, Object>>) lista
                : java.util.List.of();
    }

    private Map<String, Object> fetchApplicationsPage(String userId, String prospectId) {
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/origination/applications")
                        .queryParam("prospectId", prospectId).build())
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                // origination pagina esta ruta: devuelve un objeto `Page` con `content`, no un
                // array. Leerlo como lista hacía que Jackson fallara —"Cannot deserialize
                // ArrayList from Object value"— y el endpoint respondiera 500 en toda llamada.
                .bodyToMono(JSON_MAP)
                .block();
    }

    // ── Oferta y contrato (Fases E–F de origination) ────────────────────────
    // El BFF orquesta: presenta la oferta (resuelve productCode en el CreditController),
    // el cliente la acepta, se genera y firma el contrato → CONTRACT_SIGNED dispara la
    // activación de la cuenta (credit-portfolio) y el WalletView (wallet) vía Kafka.

    public Map<String, Object> presentOffer(String userId, String applicationId, String productCode,
                                            Double offeredAmount, Integer offeredTerm) {
        log.info("-> POST origination /applications/{}/offer productCode={}", applicationId, productCode);
        Map<String, Object> body = new HashMap<>();
        body.put("productCode", productCode);
        if (offeredAmount != null) body.put("offeredAmount", offeredAmount);
        if (offeredTerm != null) body.put("offeredTerm", offeredTerm);
        return postApplicationAction(userId, "/api/v1/origination/applications/{id}/offer", applicationId, body);
    }

    public Map<String, Object> acceptOffer(String userId, String applicationId) {
        log.info("-> POST origination /applications/{}/offer/accept", applicationId);
        return postApplicationAction(userId, "/api/v1/origination/applications/{id}/offer/accept", applicationId, null);
    }

    public Map<String, Object> rejectOffer(String userId, String applicationId) {
        log.info("-> POST origination /applications/{}/offer/reject", applicationId);
        return postApplicationAction(userId, "/api/v1/origination/applications/{id}/offer/reject", applicationId, null);
    }

    public Map<String, Object> generateContract(String userId, String applicationId, String signatureMethod) {
        log.info("-> POST origination /applications/{}/contract/generate method={}", applicationId, signatureMethod);
        return postApplicationAction(userId, "/api/v1/origination/applications/{id}/contract/generate",
                applicationId, Map.of("signatureMethod", signatureMethod));
    }

    public Map<String, Object> signContract(String userId, String applicationId, String clabeAccount,
                                            String signatureProof, String documentRef,
                                            Integer bnplDeferralDays) {
        log.info("-> POST origination /applications/{}/contract/sign", applicationId);
        Map<String, Object> body = new HashMap<>();
        body.put("clabeAccount", clabeAccount);
        body.put("signatureProof", signatureProof);
        if (documentRef != null) body.put("documentRef", documentRef);
        // Sólo viaja si el cliente lo pidió: mandar 0 y no mandar nada significan lo mismo, y una
        // clave presente en cero invita a leerla como "BNPL de cero días", que no es una decisión.
        if (bnplDeferralDays != null && bnplDeferralDays > 0) body.put("bnplDeferralDays", bnplDeferralDays);
        return postApplicationAction(userId, "/api/v1/origination/applications/{id}/contract/sign", applicationId, body);
    }

    private Map<String, Object> postApplicationAction(String userId, String uriTemplate,
                                                      String applicationId, Map<String, Object> body) {
        WebClient.RequestBodySpec spec = webClient.post()
                .uri(uriTemplate, applicationId)
                .header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON);
        WebClient.ResponseSpec resp = (body != null ? spec.bodyValue(body) : spec).retrieve();
        return resp
                .onStatus(HttpStatusCode::isError, r ->
                        r.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(r.statusCode(), detail))))
                .bodyToMono(JSON_MAP)
                .doOnNext(r -> log.info("<- origination-service status={}", r.get("status")))
                .block();
    }

    public record CreateApplicationPayload(
            String prospectId,
            String productType,
            Double requestedAmount,
            Integer requestedTerm,
            /** Distribuidor que respalda la solicitud (código o UUID del party). Null = crédito directo. */
            String promoterCode
    ) {}

    private Map<String, Object> buildBody(RegisterProspectPayload p) {
        Map<String, Object> body = new HashMap<>();
        body.put("prospectType", "INDIVIDUAL");
        body.put("firstName", p.nombres());
        body.put("lastName1", p.apellidoPaterno());
        body.put("lastName2", p.apellidoMaterno());
        body.put("curp", p.curp());
        if (p.rfc() != null) body.put("rfc", p.rfc());
        body.put("dateOfBirth", p.fechaNacimiento());
        body.put("gender", mapGender(p.genero()));
        body.put("stateOfBirth", p.estadoNacimiento());
        body.put("phone", "+52" + p.phone());
        if (p.email() != null) body.put("email", p.email());
        body.put("street", p.calle());
        body.put("exteriorNumber", p.numeroExterior());
        if (p.numeroInterior() != null) body.put("interiorNumber", p.numeroInterior());
        body.put("neighborhood", p.colonia());
        body.put("municipality", p.municipio());
        body.put("city", p.ciudad());
        body.put("state", p.estado());
        body.put("postalCode", p.codigoPostal());
        body.put("country", "MX");
        body.put("channelType", "MOBILE_APP");
        // ADR-001 / migración 005: el prospecto NO carga producto. El producto se
        // elige en una CreditApplication aparte (ver CreditController).
        body.put("privacyNoticeAccepted", p.aceptaAvisoPrivacidad());
        body.put("circuloConsentAccepted", p.aceptaCirculo());
        body.put("username", p.phone());
        body.put("password", p.password());
        if (p.documents() != null && !p.documents().isEmpty()) {
            // El expediente viaja en el alta: es cuando existen a la vez el archivo y el permiso
            // para asociarlo. `documentRef` se conserva porque es la declaración de entrega —lo
            // que el dominio ya guardaba— y ahora la acompaña el archivo de verdad.
            body.put("documents", p.documents().stream().map(d -> Map.<String, Object>of(
                    "documentType", d.documentType(),
                    "documentRef", d.fileName(),
                    "fileName", d.fileName(),
                    "contentType", d.contentType(),
                    "contentBase64", d.contentBase64())).toList());
        }
        return body;
    }

    private String mapGender(String genero) {
        return switch (genero.toUpperCase()) {
            case "H", "M_MALE" -> "MALE";
            case "M", "F", "FEMALE" -> "FEMALE";
            default -> "OTHER";
        };
    }

    public record RegisterProspectPayload(
            String phone,
            String nombres,
            String apellidoPaterno,
            String apellidoMaterno,
            String curp,
            String rfc,
            String fechaNacimiento,
            String genero,
            String estadoNacimiento,
            String email,
            String calle,
            String numeroExterior,
            String numeroInterior,
            String colonia,
            String municipio,
            String ciudad,
            String estado,
            String codigoPostal,
            boolean aceptaAvisoPrivacidad,
            boolean aceptaCirculo,
            String password,
            List<DocumentPayload> documents
    ) {}

    /** Un archivo del expediente, tal como lo manda la app. */
    public record DocumentPayload(String documentType, String fileName,
                                  String contentType, String contentBase64) {}

    public record ProspectResponse(
            String prospectId,
            String curp,
            String phone,
            String createdAt,
            String expiresAt,
            String message
    ) {}
}
