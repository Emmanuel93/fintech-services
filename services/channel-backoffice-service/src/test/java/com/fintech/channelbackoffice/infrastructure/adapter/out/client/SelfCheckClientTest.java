package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The console reads what each service says of itself; one that does not answer is reported so, never as in order. */
class SelfCheckClientTest {

    private static WebClient answering(String service) {
        ExchangeFunction exchange = req -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body("""
                        {"service":"%s","scope":"*","outcome":"IN_ORDER","checks":[
                          {"code":"A","title":"A","ok":true,"blocking":true,"items":0,"amount":0,"detail":"en orden"}]}"""
                        .formatted(service))
                .build());
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private static WebClient failing() {
        ExchangeFunction exchange = req -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    @Test
    @DisplayName("cada servicio con su reporte, en orden fijo; el que no contesta sale como sin respuesta")
    void silenceIsNotInOrder() {
        SelfCheckClient client = new SelfCheckClient(answering("commission-service"), failing(),
                answering("credit-portfolio-service"), answering("statement-service"));

        List<Map<String, Object>> reports = client.all();

        assertThat(reports).extracting(r -> r.get("service")).containsExactly(
                "commission-service", "banking-service", "credit-portfolio-service", "statement-service");
        assertThat(reports.get(0)).containsEntry("outcome", "IN_ORDER");
        assertThat(reports.get(1)).containsEntry("outcome", SelfCheckClient.UNREACHABLE);
        assertThat((List<?>) reports.get(1).get("checks")).isEmpty();
    }

    @Test
    @DisplayName("rendimiento: mil lecturas de los cuatro reportes en menos de dos segundos")
    void performance() {
        SelfCheckClient client = new SelfCheckClient(answering("commission-service"), answering("banking-service"),
                answering("credit-portfolio-service"), answering("statement-service"));
        long started = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            assertThat(client.all()).hasSize(4);
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertThat(ms).as("mil lecturas en %d ms", ms).isLessThan(2_000);
    }
}
