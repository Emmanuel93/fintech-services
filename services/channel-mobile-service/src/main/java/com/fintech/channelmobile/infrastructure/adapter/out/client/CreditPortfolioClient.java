package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CreditPortfolioClient {

    private static final Logger log = LoggerFactory.getLogger(CreditPortfolioClient.class);

    private static final ParameterizedTypeReference<List<CreditAccountResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Disposiciones y cuotas viajan sin tipar: el BFF las reenvía tal cual las da el dominio. */
    private static final ParameterizedTypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public CreditPortfolioClient(@Qualifier("creditPortfolioWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public List<CreditAccountResponse> getAccountsByPartyId(UUID partyId, String userId) {
        log.info("-> GET credit-portfolio-service /api/v1/portfolio/accounts?partyId={}", partyId);
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/portfolio/accounts")
                        .queryParam("partyId", partyId)
                        .build())
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "credit-portfolio-service error: " + resp.statusCode())))
                .bodyToMono(LIST_TYPE)
                .doOnNext(list -> log.info("<- credit-portfolio-service {} accounts for partyId={}", list.size(), partyId))
                .block();
    }

    /** Las compras y disposiciones de la cuenta — lo que el titular puede llegar a diferir. */
    public List<Map<String, Object>> getDispositions(UUID creditAccountId, String userId) {
        log.info("-> GET credit-portfolio-service /api/v1/portfolio/accounts/{}/dispositions", creditAccountId);
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/{id}/dispositions", creditAccountId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "credit-portfolio-service error: " + resp.statusCode())))
                .bodyToMono(MAP_LIST_TYPE)
                .block();
    }

    /** El calendario de pagos de la cuenta — de aquí sale la cuota que el cliente decide saltar. */
    public List<Map<String, Object>> getSchedule(UUID creditAccountId, String userId) {
        log.info("-> GET credit-portfolio-service /api/v1/portfolio/accounts/{}/amortization-schedule", creditAccountId);
        return webClient.get()
                .uri("/api/v1/portfolio/accounts/{id}/amortization-schedule", creditAccountId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "credit-portfolio-service error: " + resp.statusCode())))
                .bodyToMono(MAP_LIST_TYPE)
                .block();
    }

    /**
     * Difiere una compra ya hecha. El plazo nulo cae al del producto.
     *
     * <p>Los errores viajan con su status: un 409 —fuera de ventana, plazo que el producto no
     * admite— tiene que llegar a la pantalla como lo que es, un no con motivo, y no como un
     * fallo genérico.
     */
    public void deferDisposition(UUID creditAccountId, UUID dispositionId,
                                 Integer termPeriods, String userId) {
        log.info("-> POST credit-portfolio-service /api/v1/portfolio/accounts/{}/dispositions/{}/defer",
                creditAccountId, dispositionId);
        webClient.post()
                .uri("/api/v1/portfolio/accounts/{id}/dispositions/{did}/defer", creditAccountId, dispositionId)
                .header("X-User-Id", userId)
                // singletonMap y no Map.of: el plazo nulo es un valor legítimo —significa
                // "el que traiga el producto"— y Map.of no admite nulos.
                .bodyValue(java.util.Collections.singletonMap("termPeriods", termPeriods))
                .retrieve()
                .onStatus(HttpStatusCode::isError, CreditPortfolioClient::conMotivo)
                .toBodilessEntity()
                .block();
    }

    /** El cliente salta un pago suyo. El tope por ciclo lo aplica cartera, que es quien lo sabe. */
    public void skipInstallment(UUID creditAccountId, UUID installmentId, String userId) {
        log.info("-> POST credit-portfolio-service /api/v1/portfolio/accounts/{}/installments/{}/skip",
                creditAccountId, installmentId);
        webClient.post()
                .uri("/api/v1/portfolio/accounts/{id}/installments/{iid}/skip", creditAccountId, installmentId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, CreditPortfolioClient::conMotivo)
                .toBodilessEntity()
                .block();
    }

    /** Conserva el status y el cuerpo del dominio: el motivo del rechazo es la mitad de la respuesta. */
    private static Mono<? extends Throwable> conMotivo(org.springframework.web.reactive.function.client.ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(cuerpo -> new ResponseStatusException(resp.statusCode(),
                        cuerpo.isBlank() ? "credit-portfolio-service " + resp.statusCode() : cuerpo));
    }

    public record CreditAccountResponse(
            UUID       creditAccountId,
            UUID       contractId,
            String     contractNumber,
            String     productCode,
            String     productType,
            String     productBehavior,
            UUID       obligorPartyId,
            String     status,
            BigDecimal nominalRate,
            BigDecimal moratoriumRate,
            Integer    assignedTerm,
            BigDecimal creditLimit,
            BigDecimal principalBalance,
            BigDecimal accruedInterestBalance,
            BigDecimal penaltyBalance,
            BigDecimal availableCredit,
            BigDecimal totalDebt,
            String     amortizationType,
            String     riskTier,
            String     clabeAccount,
            int        daysDelinquent,
            Instant    createdAt,
            Instant    activatedAt,

            // Avance del plan de pagos, que resuelve credit-portfolio a partir
            // del calendario. Es lo que permite a la app decir "Pago 3 de 12" y
            // cuándo vence el siguiente. Nulos en revolventes y mientras la
            // cuenta no tenga calendario generado.
            Integer    paidInstallments,
            Integer    totalInstallments,
            Integer    nextInstallmentNumber,
            LocalDate  paymentDueDate,
            BigDecimal minimumPayment,
            BigDecimal principalPaid,
            BigDecimal overdueAmount,
            Integer    overdueInstallments
    ) {}
}
