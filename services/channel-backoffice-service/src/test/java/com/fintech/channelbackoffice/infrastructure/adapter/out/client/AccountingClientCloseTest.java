package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The month-end close through the BFF: a month that does not reconcile comes back as 409 with the reconciliation, not
 * as a gateway error, so the screen can list the checks that are off.
 */
class AccountingClientCloseTest {

    private static AccountingClient client(HttpStatus status, String body) {
        ExchangeFunction exchange = req -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE).body(body).build());
        return new AccountingClient(WebClient.builder().exchangeFunction(exchange).build());
    }

    @Test
    @DisplayName("a month that reconciles: 200 with the checks")
    void closed() {
        var outcome = client(HttpStatus.OK, """
                {"period":"202610","status":"CLOSED","outcome":"RECONCILED","checks":[]}""").closePeriod("202610");

        assertThat(outcome.status()).isEqualTo(200);
        assertThat(outcome.body()).containsEntry("status", "CLOSED");
    }

    @Test
    @DisplayName("a month that does not reconcile: 409 with the report, as accounting gave it")
    void notReconciled() {
        var outcome = client(HttpStatus.CONFLICT, """
                {"period":"202610","status":"OPEN","outcome":"DIFFERENCES",
                 "message":"No se puede cerrar el período 202610: 1 comprobación no concilia",
                 "checks":[{"code":"CAPITAL_1201","ok":false,"blocking":true}]}""").closePeriod("202610");

        assertThat(outcome.status()).isEqualTo(409);
        assertThat(outcome.body()).containsEntry("outcome", "DIFFERENCES").containsKey("checks");
    }

    @Test
    @DisplayName("any other failure is still an error")
    void otherErrors() {
        assertThatThrownBy(() -> client(HttpStatus.INTERNAL_SERVER_ERROR, "{}").closePeriod("202610"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
