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

/**
 * wallet-service es el front síncrono de todo movimiento de dinero (pago, disposición,
 * retiro). Internamente orquesta contra payments/credit-portfolio vía Kafka — el BFF
 * solo necesita el request/response síncrono, nunca habla con Kafka ni con payments
 * directamente.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final WebClient webClient;

    private static final ParameterizedTypeReference<List<MovementResponse>> MOVEMENTS_TYPE =
            new ParameterizedTypeReference<>() {};

    public WalletClient(@Qualifier("walletWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public WalletViewResponse getWalletView(UUID creditAccountId, String userId) {
        log.info("-> GET wallet-service /api/v1/wallet/{}", creditAccountId);
        return webClient.get()
                .uri("/api/v1/wallet/{creditAccountId}", creditAccountId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "wallet-service error: " + resp.statusCode())))
                .bodyToMono(WalletViewResponse.class)
                .block();
    }

    public PaymentInstructionResponse createPaymentInstruction(UUID creditAccountId, String userId,
                                                                String paymentMethod, BigDecimal amount,
                                                                String paymentType) {
        log.info("-> POST wallet-service /api/v1/wallet/{}/payment-instructions", creditAccountId);
        return webClient.post()
                .uri("/api/v1/wallet/{creditAccountId}/payment-instructions", creditAccountId)
                .header("X-User-Id", userId)
                .bodyValue(new CreatePaymentInstructionPayload(paymentMethod, amount, paymentType, null))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "wallet-service error: " + resp.statusCode())))
                .bodyToMono(PaymentInstructionResponse.class)
                .block();
    }

    public void requestDisposition(UUID creditAccountId, String userId, BigDecimal amount,
                                   String dispositionType, UUID beneficiaryPartyId,
                                   Integer termPeriods) {
        log.info("-> POST wallet-service /api/v1/wallet/{}/dispositions type={}", creditAccountId, dispositionType);
        webClient.post()
                .uri("/api/v1/wallet/{creditAccountId}/dispositions", creditAccountId)
                .header("X-User-Id", userId)
                .bodyValue(new RequestDispositionPayload(amount, dispositionType, beneficiaryPartyId, null, termPeriods))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "wallet-service error: " + resp.statusCode())))
                .toBodilessEntity()
                .block();
    }

    public List<MovementResponse> getMovements(UUID creditAccountId, String userId) {
        log.info("-> GET wallet-service /api/v1/wallet/{}/movements", creditAccountId);
        return webClient.get()
                .uri("/api/v1/wallet/{creditAccountId}/movements", creditAccountId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "wallet-service error: " + resp.statusCode())))
                .bodyToMono(MOVEMENTS_TYPE)
                .block();
    }

    public WalletWithdrawalResponse withdraw(UUID creditAccountId, String userId, String method,
                                              BigDecimal amount, String payeeAccount) {
        log.info("-> POST wallet-service /api/v1/wallet/{}/withdrawals", creditAccountId);
        return webClient.post()
                .uri("/api/v1/wallet/{creditAccountId}/withdrawals", creditAccountId)
                .header("X-User-Id", userId)
                .bodyValue(new WithdrawFromWalletPayload(method, amount, payeeAccount))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "wallet-service error: " + resp.statusCode())))
                .bodyToMono(WalletWithdrawalResponse.class)
                .block();
    }

    public record CreatePaymentInstructionPayload(
            String paymentMethod, BigDecimal amount, String paymentType, Instant scheduledAt) {}

    public record RequestDispositionPayload(
            BigDecimal amount, String dispositionType, UUID beneficiaryPartyId, String payeeAccount,
            Integer termPeriods) {}

    public record WithdrawFromWalletPayload(String method, BigDecimal amount, String payeeAccount) {}

    public record WalletViewResponse(
            UUID walletId,
            UUID creditAccountId,
            UUID obligorPartyId,
            String productType,
            BigDecimal principalBalance,
            BigDecimal accruedInterestBalance,
            BigDecimal penaltyBalance,
            BigDecimal totalDebt,
            BigDecimal availableCredit,
            BigDecimal walletBalance,
            BigDecimal minimumPayment,
            LocalDate paymentDueDate,
            BigDecimal nextInstallmentAmount,
            String status,
            Instant lastUpdatedAt,
            boolean hasRegisteredClabe
    ) {}

    public record PaymentInstructionResponse(
            UUID instructionId,
            UUID creditAccountId,
            String paymentMethod,
            BigDecimal amount,
            String paymentType,
            String status,
            Instant expiresAt,
            String paymentRef,
            Instant createdAt
    ) {}

    public record WalletWithdrawalResponse(
            UUID withdrawalId,
            UUID creditAccountId,
            String method,
            BigDecimal amount,
            String payeeAccount,
            String status,
            String externalRef,
            Instant createdAt
    ) {}

    public record MovementResponse(
            UUID movementId,
            UUID creditAccountId,
            String type,        // DISPOSITION | WITHDRAWAL | PAYMENT
            String direction,   // CREDIT | DEBIT
            BigDecimal amount,
            String description,
            String status,
            String reference,
            Instant createdAt
    ) {}
}
