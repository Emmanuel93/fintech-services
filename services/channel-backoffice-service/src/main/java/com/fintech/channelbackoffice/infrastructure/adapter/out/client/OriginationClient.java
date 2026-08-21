package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a origination-service: la bandeja de solicitudes y la decisión manual.
 *
 * <p>El dominio pagina el listado; el backoffice lo consume como arreglo (la
 * bandeja de decisión es corta por naturaleza), así que se pide una página grande
 * y se devuelve su contenido. La decisión vive bajo {@code /underwriting} en el
 * dominio; aquí se expone bajo {@code /origination} por coherencia con el resto
 * de la bandeja.
 */
@Component
public class OriginationClient {

    private static final Logger log = LoggerFactory.getLogger(OriginationClient.class);

    /** Tope de la bandeja: es cola de decisión (pocas filas), no la cartera entera. */
    private static final int QUEUE_SIZE = 200;

    private final WebClient webClient;

    public OriginationClient(@Qualifier("originationWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Bandeja de solicitudes, filtrable por los tres ejes que el dominio ya soporta:
     * estado, producto, audiencia (B2C|B2B2C|B2B), rango de alta (from/to) y texto libre.
     * Devuelve el contenido de una página amplia — es cola de decisión, no la cartera.
     */
    public List<ApplicationResponse> queue(String status, String productType, String targetAudience,
                                           String from, String to, String q) {
        log.info("-> GET origination-service /api/v1/origination/applications status={} productType={} "
                + "targetAudience={} from={} to={} q={}", status, productType, targetAudience, from, to, q);
        PageResponse<ApplicationResponse> page = webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/origination/applications")
                            .queryParam("page", 0)
                            .queryParam("size", QUEUE_SIZE);
                    if (status != null && !status.isBlank())                 b.queryParam("status", status);
                    if (productType != null && !productType.isBlank())       b.queryParam("productType", productType);
                    if (targetAudience != null && !targetAudience.isBlank()) b.queryParam("targetAudience", targetAudience);
                    if (from != null && !from.isBlank())                     b.queryParam("from", from);
                    if (to != null && !to.isBlank())                         b.queryParam("to", to);
                    if (q != null && !q.isBlank())                           b.queryParam("q", q);
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(new ParameterizedTypeReference<PageResponse<ApplicationResponse>>() {})
                .block();
        return page == null || page.content() == null ? List.of() : page.content();
    }

    /** Detalle completo de una solicitud (para la mesa de análisis): oferta, contrato y decisión. */
    public ApplicationDetailResponse getById(UUID applicationId) {
        log.info("-> GET origination-service /api/v1/origination/applications/{}", applicationId);
        return webClient.get()
                .uri("/api/v1/origination/applications/{id}", applicationId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(ApplicationDetailResponse.class)
                .block();
    }

    /** Lo que capturó el prospecto (datos personales, domicilio, consentimientos, documentos). */
    public ProspectDetailResponse getProspect(UUID prospectId) {
        log.info("-> GET origination-service /api/v1/origination/prospects/{}", prospectId);
        return webClient.get()
                .uri("/api/v1/origination/prospects/{id}", prospectId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(ProspectDetailResponse.class)
                .block();
    }

    /** Solicitudes de un prospecto (para el expediente del cliente). */
    /**
     * Prospectos con ese contacto exacto. Devuelve vacío —no lanza— cuando no hay ninguno:
     * quien pregunta está buscando, y "no encontré" es una respuesta, no un fallo.
     */
    public List<ProspectDetailResponse> lookupProspects(String email, String phone, String curp) {
        log.info("-> GET origination /prospects/lookup email={} phone={} curp={}",
                email != null, phone != null, curp != null);
        return webClient.get()
                .uri(uri -> {
                    // Los valores van como variables de plantilla, no incrustados en la URL.
                    // Es la diferencia entre que `julieta.e2e+1786421297@kredius.mx` llegue
                    // entero o que el `+` se lea como un espacio del otro lado: el builder
                    // sólo codifica de forma estricta lo que expande de una variable. Y el
                    // síntoma no sería un error, sino un "no hay nadie con ese correo".
                    var b = uri.path("/api/v1/origination/prospects/lookup");
                    Map<String, Object> vars = new LinkedHashMap<>();
                    if (email != null && !email.isBlank()) { b.queryParam("email", "{email}"); vars.put("email", email); }
                    if (phone != null && !phone.isBlank()) { b.queryParam("phone", "{phone}"); vars.put("phone", phone); }
                    if (curp  != null && !curp.isBlank())  { b.queryParam("curp",  "{curp}");  vars.put("curp", curp); }
                    return b.build(vars);
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<ProspectDetailResponse>>() {})
                .block();
    }

    /** Qué archivos tiene el expediente, sin traérselos. */
    /**
     * Dictamina un documento del expediente.
     *
     * <p>El autor lo pone el BFF desde la sesión, no el navegador: la firma es lo que vuelve
     * evidencia a un dictamen, y dejarla en manos del cliente permitiría firmar por otro.
     */
    public Map<String, Object> reviewDocument(UUID prospectId, String documentType, String decision,
                                              String reviewedBy, String rejectionReason) {
        log.info("-> PUT origination /prospects/{}/documents/{}/review {} por {}",
                prospectId, documentType, decision, reviewedBy);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("decision", decision);
        body.put("reviewedBy", reviewedBy);
        if (rejectionReason != null) body.put("rejectionReason", rejectionReason);

        return webClient.put()
                .uri("/api/v1/origination/prospects/{id}/documents/{type}/review", prospectId, documentType)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    public List<Map<String, Object>> documents(UUID prospectId) {
        log.info("-> GET origination /prospects/{}/documents", prospectId);
        return webClient.get()
                .uri("/api/v1/origination/prospects/{id}/documents", prospectId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
    }

    /**
     * Los bytes de un documento y su tipo de contenido.
     *
     * Se devuelven juntos porque separarlos obligaría a una segunda llamada para saber si lo que
     * se acaba de bajar es un JPEG o un PDF, que es justo lo que decide cómo mostrarlo.
     */
    public DocumentContent documentContent(UUID prospectId, String documentType) {
        log.info("-> GET origination /prospects/{}/documents/{}/file", prospectId, documentType);
        var response = webClient.get()
                .uri("/api/v1/origination/prospects/{id}/documents/{type}/file", prospectId, documentType)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .toEntity(byte[].class)
                .block();
        if (response == null || response.getBody() == null) return null;
        var mediaType = response.getHeaders().getContentType();
        return new DocumentContent(response.getBody(),
                mediaType == null ? "application/octet-stream" : mediaType.toString());
    }

    /** Un archivo del expediente, listo para reenviarse al navegador. */
    public record DocumentContent(byte[] bytes, String contentType) {}

    public List<ApplicationResponse> findByProspect(UUID prospectId) {
        log.info("-> GET origination-service /api/v1/origination/applications?prospectId={}", prospectId);
        PageResponse<ApplicationResponse> page = webClient.get()
                .uri(uri -> uri.path("/api/v1/origination/applications")
                        .queryParam("prospectId", prospectId)
                        .queryParam("size", QUEUE_SIZE)
                        .build())
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .bodyToMono(new ParameterizedTypeReference<PageResponse<ApplicationResponse>>() {})
                .block();
        return page == null || page.content() == null ? List.of() : page.content();
    }

    /**
     * Decisión manual (aprobar/rechazar). El dominio la expone bajo /underwriting.
     * {@code decidedBy} se resuelve del empleado autenticado — la consola no lo teclea.
     */
    public void decide(UUID applicationId, boolean approved, String rejectionReason) {
        String decidedBy = DomainClientSupport.currentStaffUserId();
        log.info("-> POST origination-service /underwriting/applications/{}/decision approved={} by={}",
                applicationId, approved, decidedBy);
        webClient.post()
                .uri("/api/v1/origination/underwriting/applications/{id}/decision", applicationId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(Map.of(
                        "decidedBy", decidedBy == null ? "backoffice" : decidedBy,
                        "approved", approved,
                        "rejectionReason", rejectionReason == null ? "" : rejectionReason))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .toBodilessEntity()
                .block();
    }

    /**
     * Pide documentos adicionales (E6): la solicitud pasa a PENDING_DOCUMENTS. El {@code requestedBy}
     * sale del empleado autenticado, no de la consola.
     */
    public void requestDocuments(UUID applicationId, String note) {
        String requestedBy = DomainClientSupport.currentStaffUserId();
        log.info("-> POST origination-service /underwriting/applications/{}/request-documents by={}",
                applicationId, requestedBy);
        webClient.post()
                .uri("/api/v1/origination/underwriting/applications/{id}/request-documents", applicationId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(Map.of(
                        "decidedBy", requestedBy == null ? "backoffice" : requestedBy,
                        "note", note == null ? "" : note))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .toBodilessEntity()
                .block();
    }

    /** Marca documentos recibidos (E6): la solicitud vuelve a la mesa de revisión. */
    public void markDocumentsReceived(UUID applicationId) {
        log.info("-> POST origination-service /underwriting/applications/{}/documents-received", applicationId);
        webClient.post()
                .uri("/api/v1/origination/underwriting/applications/{id}/documents-received", applicationId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("origination-service", r))
                .toBodilessEntity()
                .block();
    }

    /** Solo los campos que el backoffice pinta; Jackson ignora el resto del DTO del dominio. */
    public record ApplicationResponse(
            UUID       applicationId,
            /** Folio legible: es lo que el cliente dicta por teléfono, no el UUID. */
            String     folio,
            UUID       prospectId,
            String     productType,
            /** AUTO_APPROVED | MANUAL_REVIEW | REJECTED — la bandeja filtra por esto. */
            String     decision,
            BigDecimal requestedAmount,
            Integer    requestedTerm,
            String     status,
            String     approvalFlow,
            String     riskLevel,
            String     decidedBy,
            String     rejectionReason,
            BigDecimal offeredAmount,
            BigDecimal offeredLine,
            Integer    offeredTerm,
            BigDecimal nominalRate,
            BigDecimal cat,
            Instant    createdAt
    ) {}

    /**
     * Detalle completo de la solicitud. Superset de {@link ApplicationResponse} con la
     * oferta, el contrato y la traza de decisión que sólo importan en la ficha, no en la
     * bandeja. Jackson ignora los campos que el dominio no envíe.
     */
    public record ApplicationDetailResponse(
            UUID       applicationId,
            UUID       prospectId,
            String     prospectType,
            String     productType,
            String     status,
            BigDecimal requestedAmount,
            Integer    requestedTerm,
            UUID       scoreRequestId,
            String     riskLevel,
            String     decision,
            String     rejectionReason,
            String     approvalFlow,
            String     decidedBy,
            Instant    rejectedAt,
            String     productCode,
            String     productBehavior,
            BigDecimal offeredAmount,
            BigDecimal offeredLine,
            Integer    offeredTerm,
            BigDecimal nominalRate,
            BigDecimal cat,
            Instant    validUntil,
            Instant    offerPresentedAt,
            Instant    offerAcceptedAt,
            String     contractNumber,
            String     signatureMethod,
            String     clabeAccount,
            String     documentRef,
            Instant    contractSignedAt,
            Instant    createdAt,
            Instant    updatedAt
    ) {}

    /** Lo que capturó el prospecto; se reenvía tal cual al frontend. */
    public record ProspectDetailResponse(
            String  prospectId,
            String  prospectType,
            String  status,
            String  firstName,
            String  lastName1,
            String  lastName2,
            String  curp,
            String  rfc,
            java.time.LocalDate dateOfBirth,
            String  gender,
            String  stateOfBirth,
            String  phone,
            String  email,
            Address address,
            String  channelType,
            boolean privacyNoticeAccepted,
            Instant privacyNoticeAcceptedAt,
            boolean circuloConsentAccepted,
            Instant circuloConsentAcceptedAt,
            List<Document> documents,
            Instant createdAt,
            Instant expiresAt
    ) {
        public record Address(
                String street, String exteriorNumber, String interiorNumber,
                String neighborhood, String municipality, String city, String state,
                String postalCode, String country) {}

        public record Document(String documentType, String documentRef,
                               String incomeProofType, Instant uploadedAt) {}

        /** Nombre completo del sujeto, para etiquetar sus créditos sin una llamada extra a party. */
        public String fullName() {
            return java.util.stream.Stream.of(firstName, lastName1, lastName2)
                    .filter(s -> s != null && !s.isBlank())
                    .reduce((a, b) -> a + " " + b).orElse(null);
        }
    }

    /** La página tal como la serializa Spring Data. */
    public record PageResponse<T>(
            List<T> content,
            int     number,
            int     size,
            long    totalElements,
            int     totalPages
    ) {}
}
