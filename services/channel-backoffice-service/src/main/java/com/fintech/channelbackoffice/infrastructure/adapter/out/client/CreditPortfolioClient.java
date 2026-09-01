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
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a credit-portfolio-service.
 *
 * <p>{@link #listByParty(UUID)} exige {@code partyId} porque es lo único que el servicio sabe hacer
 * hoy. El listado paginado y filtrable de toda la cartera —el endpoint que sostiene la pantalla
 * principal del backoffice— es trabajo de la fase 1.
 */
@Component
public class CreditPortfolioClient {

    private static final Logger log = LoggerFactory.getLogger(CreditPortfolioClient.class);

    /** Los programas viajan sin tipar: el BFF los reenvía tal cual los da el dominio. */
    private static final ParameterizedTypeReference<Map<String, Object>> MAPA =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public CreditPortfolioClient(@Qualifier("creditPortfolioWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Identidad del empleado que hace la consulta, para los servicios de
     * dominio.
     *
     * <p>Los servicios autentican por `X-User-Id`/`X-Roles` —el gateway los
     * inyecta al llegar al BFF— y hay que reenviarlos: sin ellos la llamada
     * sale 401. Se lee del contexto de seguridad en vez de arrastrar el
     * `HttpServletRequest` por cada firma, y así queda además rastro de **quién**
     * consultó la cartera, no sólo de que alguien lo hizo.
     */
    private java.util.function.Consumer<org.springframework.http.HttpHeaders> staffIdentity() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        String staffUserId = auth == null ? null : String.valueOf(auth.getPrincipal());
        String roles = auth == null ? "" : auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(java.util.stream.Collectors.joining(","));
        return h -> {
            if (staffUserId != null && !staffUserId.isBlank()) h.set("X-User-Id", staffUserId);
            if (!roles.isBlank()) h.set("X-Roles", roles);
            h.set("X-Channel", "BACKOFFICE");
        };
    }

    // ── Programas de apoyo por contingencia (BK-32, BK-33) ───────────────────
    //
    // El maker-checker lo resuelve el dominio a partir de `X-User-Id`, que `staffIdentity()` ya
    // reenvía. El BFF no lo replica: dos sitios decidiendo quién puede autorizar es un sitio de más
    // donde la separación de funciones puede aflojarse sin que nadie lo note.

    public Map<String, Object> proponerPrograma(Object cuerpo) {
        log.info("-> POST credit-portfolio-service /api/v1/portfolio/relief-programs");
        return webClient.post()
                .uri("/api/v1/portfolio/relief-programs")
                .headers(staffIdentity())
                .bodyValue(cuerpo)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(MAPA)
                .block();
    }

    /** Simula el padrón: a cuántas cuentas alcanza, sin mover un solo vencimiento. */
    public Map<String, Object> padronDelPrograma(UUID programId) {
        log.info("-> GET credit-portfolio-service /api/v1/portfolio/relief-programs/{}/padron", programId);
        return webClient.get()
                .uri("/api/v1/portfolio/relief-programs/{id}/padron", programId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(MAPA)
                .block();
    }

    public Map<String, Object> autorizarPrograma(UUID programId) {
        log.info("-> POST credit-portfolio-service /api/v1/portfolio/relief-programs/{}/approve", programId);
        return webClient.post()
                .uri("/api/v1/portfolio/relief-programs/{id}/approve", programId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(MAPA)
                .block();
    }

    public Map<String, Object> otorgarPrograma(UUID programId) {
        log.info("-> POST credit-portfolio-service /api/v1/portfolio/relief-programs/{}/grant", programId);
        return webClient.post()
                .uri("/api/v1/portfolio/relief-programs/{id}/grant", programId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(MAPA)
                .block();
    }

    public List<CreditAccountResponse> listByParty(UUID partyId) {
        log.info("-> GET credit-portfolio-service /api/v1/portfolio/accounts?partyId={}", partyId);
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/portfolio/accounts")
                        .queryParam("partyId", partyId)
                        .build())
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToFlux(CreditAccountResponse.class)
                .collectList()
                .block();
    }

    /**
     * Listado paginado. La página la arma credit-portfolio: pedir todo y
     * recortar aquí sería mover la cartera entera por la red en cada carga.
     */
    public PageResponse<CreditAccountResponse> search(String status, String productType, String q,
                                                        Integer minDaysDelinquent, Integer maxDaysDelinquent,
                                                        java.util.Collection<UUID> partyIds,
                                                        int page, int size, String sort) {
        log.info("-> GET credit-portfolio-service /accounts/search status={} page={} size={}", status, page, size);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/portfolio/accounts/search")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (status != null && !status.isBlank())            b.queryParam("status", status);
                    if (productType != null && !productType.isBlank())  b.queryParam("productType", productType);
                    if (q != null && !q.isBlank())                      b.queryParam("q", q);
                    if (minDaysDelinquent != null)                      b.queryParam("minDaysDelinquent", minDaysDelinquent);
                    if (maxDaysDelinquent != null)                      b.queryParam("maxDaysDelinquent", maxDaysDelinquent);
                    if (partyIds != null && !partyIds.isEmpty())        b.queryParam("partyIds", partyIds.toArray());
                    if (sort != null && !sort.isBlank())                b.queryParam("sort", sort);
                    return b.build();
                })
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(new ParameterizedTypeReference<PageResponse<CreditAccountResponse>>() {})
                .block();
    }

    /** Distribución de la cartera agrupada por dimensión (status | productType | dpdBucket). */
    public List<PortfolioStatResponse> stats(String groupBy) {
        log.info("-> GET credit-portfolio-service /accounts/stats groupBy={}", groupBy);
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/portfolio/accounts/stats")
                        .queryParam("groupBy", groupBy)
                        .build())
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToFlux(PortfolioStatResponse.class)
                .collectList()
                .block();
    }

    /** Mezcla por producto para el tablero, agrupada en la base. */
    public List<ProductMixResponse> productMix() {
        log.info("-> GET credit-portfolio-service /accounts/product-mix");
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/product-mix")
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToFlux(ProductMixResponse.class)
                .collectList()
                .block();
    }

    /** Agregados de cartera; los calcula la base en una sola pasada. */
    public PortfolioSummaryResponse summary() {
        log.info("-> GET credit-portfolio-service /accounts/summary");
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/summary")
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(PortfolioSummaryResponse.class)
                .block();
    }

    /** El calendario de la cuenta: es el seguimiento periodo a periodo. */
    public List<InstallmentResponse> schedule(UUID creditAccountId) {
        log.info("-> GET credit-portfolio-service /accounts/{}/amortization-schedule", creditAccountId);
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/{id}/amortization-schedule", creditAccountId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToFlux(InstallmentResponse.class)
                .collectList()
                .block();
    }

    public List<DispositionResponse> dispositions(UUID creditAccountId) {
        log.info("-> GET credit-portfolio-service /accounts/{}/dispositions", creditAccountId);
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/{id}/dispositions", creditAccountId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToFlux(DispositionResponse.class)
                .collectList()
                .block();
    }

    /** Hidrata cuentas por creditAccountId en lote (una consulta). Vacío si no hay ids. */
    public List<CreditAccountResponse> batch(java.util.Collection<UUID> creditAccountIds) {
        if (creditAccountIds == null || creditAccountIds.isEmpty()) {
            return List.of();
        }
        log.info("-> GET credit-portfolio-service /accounts/batch ({} ids)", creditAccountIds.size());
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/portfolio/accounts/batch")
                        .queryParam("ids", creditAccountIds.toArray())
                        .build())
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToFlux(CreditAccountResponse.class)
                .collectList()
                .block();
    }

    /**
     * Sella la sucursal de origen de un crédito ya existente (reconciliación).
     *
     * <p>Idempotente y sin sobrescritura del lado del dominio: un crédito que ya tiene sucursal se
     * queda con la suya. Reasignar la cartera no puede reescribir a qué rama se le atribuyó el
     * ingreso de meses anteriores.
     */
    public CreditAccountResponse sealOriginUnit(UUID creditAccountId, String originUnitCode) {
        log.info("-> PUT credit-portfolio /accounts/{}/origin-unit code={}", creditAccountId, originUnitCode);
        return webClient.put()
                .uri("/api/v1/portfolio/accounts/{id}/origin-unit", creditAccountId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(java.util.Map.of("originUnitCode", originUnitCode))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(CreditAccountResponse.class)
                .block();
    }

    public CreditAccountResponse getById(UUID creditAccountId) {
        log.info("-> GET credit-portfolio-service /api/v1/portfolio/accounts/{}", creditAccountId);
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/{id}", creditAccountId)
                .headers(staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(CreditAccountResponse.class)
                .block();
    }

    /**
     * Cartera por unidad de ORIGEN, acotada a un conjunto de códigos.
     *
     * <p>Una llamada por consulta, sin importar cuántas unidades traiga el subárbol: el agregado
     * lo hace la base del dueño. Sumar por unidad desde aquí sería el bucle que el invariante
     * prohíbe.
     */
    public List<OriginUnitStat> statsByOriginUnit(Collection<String> unitCodes) {
        log.info("-> GET credit-portfolio /accounts/stats/by-origin-unit unidades={}",
                unitCodes == null ? 0 : unitCodes.size());
        List<OriginUnitStat> rows = webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/portfolio/accounts/stats/by-origin-unit");
                    if (unitCodes != null && !unitCodes.isEmpty()) {
                        b.queryParam("unitCodes", unitCodes.toArray());
                    }
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("credit-portfolio-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<OriginUnitStat>>() {})
                .block();
        return rows == null ? List.of() : rows;
    }

    /** Cartera de una unidad de origen, con los tramos IFRS-9 sin colapsar. */
    public record OriginUnitStat(
            String unitCode,
            long accounts,
            long delinquentAccounts,
            BigDecimal principal,
            BigDecimal stage1Principal,
            BigDecimal stage2Principal,
            BigDecimal stage3Principal) {}

    public record CreditAccountResponse(
            UUID       creditAccountId,
            String     contractNumber,
            UUID       obligorPartyId,
            String     productCode,
            String     productType,
            String     productBehavior,
            String     status,
            BigDecimal nominalRate,
            Integer    assignedTerm,
            BigDecimal principalBalance,
            BigDecimal accruedInterestBalance,
            BigDecimal penaltyBalance,
            BigDecimal ivaBalance,
            BigDecimal creditLimit,
            BigDecimal availableCredit,
            int        daysDelinquent,
            String     riskTier,
            String     clabeAccount,
            /** La sucursal sellada al activar. Nula en los créditos anteriores al sellado. */
            String     originUnitCode,
            Instant    activatedAt,

            // Avance del plan de pagos, que cartera deriva del calendario.
            Integer    paidInstallments,
            Integer    totalInstallments,
            Integer    nextInstallmentNumber,
            LocalDate  paymentDueDate,
            BigDecimal minimumPayment,
            BigDecimal principalPaid,
            BigDecimal overdueAmount,
            Integer    overdueInstallments
    ) {}

    /** Una mensualidad del calendario. */
    public record InstallmentResponse(
            int        installmentNumber,
            LocalDate  dueDate,
            BigDecimal principalAmount,
            BigDecimal interestAmount,
            BigDecimal totalAmount,
            String     status
    ) {}

    public record DispositionResponse(
            UUID       dispositionId,
            BigDecimal amount,
            String     dispositionType,
            String     status,
            Instant    createdAt
    ) {}

    public record PortfolioSummaryResponse(
            long       activeAccounts,
            long       activeObligors,
            BigDecimal principalBalance,
            BigDecimal totalDebt,
            BigDecimal stage1Principal,
            BigDecimal stage2Principal,
            BigDecimal stage3Principal,
            long       delinquentAccounts,
            BigDecimal aCobrarProximoPeriodo,
            BigDecimal esperadoPeriodoActual,
            BigDecimal cobradoPeriodoActual
    ) {}

    public record ProductMixResponse(
            String     productType,
            String     productBehavior,
            long       accounts,
            BigDecimal capital,
            BigDecimal overdueCapital
    ) {}

    /** Una fila de /accounts/stats: la clave del grupo, su conteo y su capital. */
    public record PortfolioStatResponse(
            String     key,
            long       count,
            BigDecimal principal
    ) {}

    /** La página tal como la serializa Spring Data. */
    public record PageResponse<T>(
            List<T> content,
            int     number,
            int     size,
            long    totalElements,
            int     totalPages
    ) {}
}
