package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Acceso a accounting-service.
 *
 * <p><b>El alcance viaja resuelto.</b> Contabilidad no conoce el árbol comercial ni tiene por qué:
 * el BFF pregunta el subárbol a sales-org <b>una vez</b> y manda la lista de códigos. La alternativa
 * —que accounting mantenga una copia del árbol— obliga a sincronizarlo en dos servicios para una
 * consulta que ya viene acotada.
 *
 * <p><b>Nada se agrega aquí.</b> Los totales, el desglose por sucursal y la balanza salen agregados
 * de la base. Sumar en el BFF haría que el total del período y el de la balanza pudieran discrepar
 * sin que nadie supiera cuál es el bueno.
 */
@Component
public class AccountingClient {

    private static final Logger log = LoggerFactory.getLogger(AccountingClient.class);

    private final WebClient webClient;

    public AccountingClient(@Qualifier("accountingWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    // ── Pólizas ──────────────────────────────────────────────────────────────

    public VoucherPage vouchers(String period, List<String> unitCodes, String type,
                                UUID creditAccountId, UUID partyId, int page, int size) {
        log.info("-> GET accounting-service /vouchers period={} unidades={} page={}",
                period, unitCodes == null ? 0 : unitCodes.size(), page);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/vouchers")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (notBlank(period))            b.queryParam("period", period);
                    if (notBlank(type))              b.queryParam("type", type);
                    if (creditAccountId != null)     b.queryParam("creditAccountId", creditAccountId);
                    if (partyId != null)             b.queryParam("partyId", partyId);
                    if (unitCodes != null && !unitCodes.isEmpty()) b.queryParam("unitCodes", unitCodes.toArray());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(VoucherPage.class)
                .block();
    }

    public VoucherResponse voucher(UUID voucherId) {
        return get("/api/v1/accounting/vouchers/" + voucherId, VoucherResponse.class);
    }

    /** El período agrupado por préstamo. La agregación la hace el dominio en SQL, no el BFF. */
    public LoanMovementPage loans(String period, List<String> unitCodes, int page, int size) {
        log.info("-> GET accounting-service /loans period={} unidades={} page={}",
                period, unitCodes == null ? 0 : unitCodes.size(), page);
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/loans")
                            .queryParam("page", page)
                            .queryParam("size", size);
                    if (notBlank(period)) b.queryParam("period", period);
                    if (unitCodes != null && !unitCodes.isEmpty()) b.queryParam("unitCodes", unitCodes.toArray());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(LoanMovementPage.class)
                .block();
    }

    // ── Resumen, sucursales y balanza ────────────────────────────────────────

    public SummaryResponse summary(String period, List<String> unitCodes, boolean includeUnits) {
        log.info("-> GET accounting-service /summary period={} unidades={}",
                period, unitCodes == null ? 0 : unitCodes.size());
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/summary")
                            .queryParam("period", period)
                            .queryParam("includeUnits", includeUnits);
                    if (unitCodes != null && !unitCodes.isEmpty()) b.queryParam("unitCodes", unitCodes.toArray());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(SummaryResponse.class)
                .block();
    }

    public List<UnitMovementResponse> units(String period, List<String> unitCodes) {
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/units").queryParam("period", period);
                    if (unitCodes != null && !unitCodes.isEmpty()) b.queryParam("unitCodes", unitCodes.toArray());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<UnitMovementResponse>>() {})
                .block();
    }

    public List<TrialBalanceRowResponse> trialBalance(String period, List<String> unitCodes) {
        return trialBalance(period, unitCodes, List.of());
    }

    /** The trial balance of the branches and products asked for ({@code INSTITUCION}: what belongs to no product). */
    public List<TrialBalanceRowResponse> trialBalance(String period, List<String> unitCodes, List<String> productTypes) {
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/trial-balance").queryParam("period", period);
                    if (unitCodes != null && !unitCodes.isEmpty()) b.queryParam("unitCodes", unitCodes.toArray());
                    if (productTypes != null && !productTypes.isEmpty()) {
                        b.queryParam("productTypes", productTypes.toArray());
                    }
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<TrialBalanceRowResponse>>() {})
                .block();
    }

    /** The movement of each product in the period, within the branches asked for. */
    public List<ProductMovementResponse> products(String period, List<String> unitCodes) {
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/products").queryParam("period", period);
                    if (unitCodes != null && !unitCodes.isEmpty()) b.queryParam("unitCodes", unitCodes.toArray());
                    return b.build();
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<ProductMovementResponse>>() {})
                .block();
    }

    /** A product's accounting scheme, as the ledger booked it, between two periods (either may be open). */
    public Map<String, Object> productScheme(String productType, String from, String to) {
        return webClient.get()
                .uri(uri -> {
                    var b = uri.path("/api/v1/accounting/products/{productType}/scheme");
                    if (from != null && !from.isBlank()) b.queryParam("from", from);
                    if (to != null && !to.isBlank()) b.queryParam("to", to);
                    return b.build(productType);
                })
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    public LoanLedgerResponse loanLedger(UUID creditAccountId) {
        return get("/api/v1/accounting/accounts/" + creditAccountId + "/ledger", LoanLedgerResponse.class);
    }

    // ── Períodos y facturación ───────────────────────────────────────────────

    public List<PeriodResponse> periods() {
        return webClient.get().uri("/api/v1/accounting/periods")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<PeriodResponse>>() {})
                .block();
    }

    /**
     * The close's answer as accounting gave it: 200 with the reconciliation when the month closed, 409 with the same
     * report when it does not reconcile (the month stays open). The 409 is not an error of the BFF: the screen shows
     * the checks that are off, so it travels as it came.
     */
    public CloseOutcome closePeriod(String period) {
        return webClient.post().uri("/api/v1/accounting/periods/" + period + "/close")
                .headers(DomainClientSupport.staffIdentity())
                .exchangeToMono(r -> r.statusCode().value() == 409 || r.statusCode().is2xxSuccessful()
                        ? r.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                .map(body -> new CloseOutcome(r.statusCode().value(), body))
                        : DomainClientSupport.propagate("accounting-service", r).flatMap(Mono::error))
                .block();
    }

    /** The reconciliation of a month: the one its close ran or, while it is open, a dry run. */
    public Map<String, Object> reconciliation(String period) {
        return webClient.get().uri("/api/v1/accounting/periods/" + period + "/reconciliation")
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    public record CloseOutcome(int status, Map<String, Object> body) {}

    public Map<String, Object> reopenPeriod(String period) {
        return post("/api/v1/accounting/periods/" + period + "/reopen");
    }

    /**
     * Atribuye a su sucursal las pólizas ya asentadas de un crédito recién sellado.
     *
     * <p>No reescribe historia: la sucursal de origen es una propiedad inmutable del préstamo, y lo
     * que faltaba era el dato, no la verdad. Sin este paso, un crédito sellado hoy seguiría contando
     * bajo «Sin sucursal» hasta su siguiente movimiento contable — que puede ser el mes que entra.
     */
    public Map<String, Object> attributeUnit(UUID creditAccountId, String orgUnitCode) {
        return webClient.post()
                .uri(uri -> uri.path("/api/v1/accounting/accounts/{id}/org-unit")
                        .queryParam("orgUnitCode", orgUnitCode).build(creditAccountId))
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    /** Barrido global: cierra el hueco que deja atribuir crédito por crédito paginando cartera. */
    public Map<String, Object> sweepUnits() {
        return post("/api/v1/accounting/org-units/sweep");
    }

    public Map<String, Object> runBilling(String period) {
        log.info("-> POST accounting-service /billing-runs period={}", period);
        return webClient.post()
                .uri(uri -> uri.path("/api/v1/accounting/billing-runs").queryParam("period", period).build())
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    // ── Verbos ───────────────────────────────────────────────────────────────

    private <T> T get(String path, Class<T> type) {
        return webClient.get().uri(path)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(type).block();
    }

    private Map<String, Object> post(String path) {
        return webClient.post().uri(path)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("accounting-service", r))
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ── Contratos del dominio ────────────────────────────────────────────────

    public record VoucherLineResponse(
            String accountCode, String accountName, String accountType,
            BigDecimal debit, BigDecimal credit, String description) {}

    public record VoucherResponse(
            UUID voucherId, String voucherType, long folio, String period, Instant voucherDate,
            String orgUnitCode, String concept, String triggerEvent, String sourceEventId,
            UUID creditAccountId, UUID obligorPartyId,
            BigDecimal totalDebit, BigDecimal totalCredit, String status,
            boolean latePosting, String originalPeriod, UUID reversalRef,
            List<VoucherLineResponse> lines) {}

    public record VoucherPage(
            List<VoucherResponse> content, int page, int size, long totalElements, int totalPages) {}

    /**
     * A trial balance row: opening balance, the period's debits and credits, and the closing balance. {@code balance}
     * is the period's net movement, not the account's balance.
     */
    public record TrialBalanceRowResponse(
            String accountCode, String accountName, String accountType,
            BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal balance,
            BigDecimal openingBalance, BigDecimal closingBalance) {}

    public record UnitMovementResponse(
            String orgUnitCode, long vouchers, long loans,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest,
            BigDecimal fees, BigDecimal provision) {}

    /** A product's movement in the period; {@code INSTITUCION} is what belongs to no product. */
    public record ProductMovementResponse(
            String productType, long vouchers, long loans,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest,
            BigDecimal fees, BigDecimal provision) {}

    public record SummaryResponse(
            String period, String periodStatus,
            long vouchers, BigDecimal totalDebit, BigDecimal totalCredit, boolean balanced,
            BigDecimal orderAccounts,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest, BigDecimal fees,
            BigDecimal ivaAccrued,
            BigDecimal provision, BigDecimal writeOff, BigDecimal recovery,
            long loansRegistered,
            List<UnitMovementResponse> byUnit,
            BillingBridgeResponse billing) {}

    /** From recognized revenue (IFRS) to what was charged and has to be invoiced (the fiscal base). */
    public record BillingBridgeResponse(BigDecimal charged, BigDecimal revenue, BigDecimal iva,
                                        BigDecimal stage3NotRecognized, BigDecimal deferredFeesCharged,
                                        BigDecimal deferredFeesRecognized, BigDecimal originationCostsAmortized,
                                        BigDecimal unexplained) {}

    public record LedgerBalanceResponse(
            String accountCode, String accountName, String accountType,
            BigDecimal debit, BigDecimal credit, BigDecimal balance) {}

    public record LoanLedgerResponse(
            UUID creditAccountId, UUID obligorPartyId, String orgUnitCode,
            List<LedgerBalanceResponse> balances, List<VoucherResponse> vouchers) {}

    public record PeriodResponse(String period, String status, Instant closedAt, long vouchers) {}

    public record LoanMovementResponse(
            UUID creditAccountId, UUID obligorPartyId, String orgUnitCode,
            long vouchers, Instant firstVoucherDate, Instant lastVoucherDate,
            BigDecimal totalDebit, BigDecimal orderAccounts,
            BigDecimal accruedInterest, BigDecimal moratoriumInterest, BigDecimal fees,
            BigDecimal provision, BigDecimal writeOff, BigDecimal disbursed) {}

    public record LoanMovementPage(
            List<LoanMovementResponse> content, int page, int size, long totalElements, int totalPages) {}
}
