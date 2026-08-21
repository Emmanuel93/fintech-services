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
import java.util.UUID;

@Component
public class CreditPortfolioClient {

    private static final Logger log = LoggerFactory.getLogger(CreditPortfolioClient.class);

    private static final ParameterizedTypeReference<List<CreditAccountResponse>> LIST_TYPE =
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
