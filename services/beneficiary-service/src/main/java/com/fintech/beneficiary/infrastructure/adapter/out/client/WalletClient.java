package com.fintech.beneficiary.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * La disposición que descuenta la línea del distribuidor y manda el dinero a la beneficiaria.
 *
 * <p>Es el único punto del flujo donde la colocación toca dinero, y ocurre al aprobar — nunca al
 * invitar. El tipo es {@code THIRD_PARTY_CREDIT}: la deuda queda en la línea del distribuidor y el
 * depósito sale a la CLABE de ella.
 *
 * <p>wallet responde <b>202</b>: acepta la orden y credit-portfolio decide después si el cupo
 * alcanza. Por eso la colocación pasa a {@code DISBURSING} y no directo a desembolsada.
 */
@Component
public class WalletClient {

    private static final Logger log = LoggerFactory.getLogger(WalletClient.class);

    private final WebClient webClient;

    public WalletClient(@Qualifier("walletWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public void requestThirdPartyDisposition(UUID creditAccountId, UUID distributorPartyId,
                                             BigDecimal amount, UUID beneficiaryPartyId,
                                             String beneficiaryClabe, int termPeriods) {
        log.info("-> POST wallet /api/v1/wallet/{}/dispositions THIRD_PARTY_CREDIT beneficiario={}",
                creditAccountId, beneficiaryPartyId);
        webClient.post()
                .uri("/api/v1/wallet/{creditAccountId}/dispositions", creditAccountId)
                // wallet toma el obligado del X-User-Id, igual que si viniera del gateway: el
                // deudor es el distribuidor aunque el dinero salga a otra cuenta.
                .header("X-User-Id", distributorPartyId.toString())
                .bodyValue(new DispositionPayload(amount, "THIRD_PARTY_CREDIT",
                        beneficiaryPartyId, beneficiaryClabe, termPeriods))
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "wallet-service rechazó la disposición: " + resp.statusCode())))
                .toBodilessEntity()
                .block();
    }

    public record DispositionPayload(
            BigDecimal amount,
            String dispositionType,
            UUID beneficiaryPartyId,
            String payeeAccount,
            Integer termPeriods) {}
}
