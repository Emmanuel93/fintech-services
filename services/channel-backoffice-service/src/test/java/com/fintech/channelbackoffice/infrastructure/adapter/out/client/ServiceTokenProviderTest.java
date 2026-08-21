package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import com.fintech.channelbackoffice.infrastructure.config.ChannelBackofficeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La credencial con la que el canal habla por su cuenta con identity.
 *
 * <p>Lo que hay que garantizar no es sólo que obtenga un token: es que <b>nunca lance</b>. Vive en
 * el camino de la auditoría, que es fire-and-forget, así que identity caído o mal configurado tiene
 * que costar el nombre del empleado y jamás la navegación.
 */
class ServiceTokenProviderTest {

    private final AtomicInteger llamadas = new AtomicInteger();

    private ServiceTokenProvider provider(String secret, Supplier<ClientResponse> respuesta) {
        ExchangeFunction intercambio = req -> {
            llamadas.incrementAndGet();
            return Mono.just(respuesta.get());
        };
        var props = new ChannelBackofficeProperties();
        props.getServiceClient().setClientId("channel-backoffice-service");
        props.getServiceClient().setSecret(secret);
        return new ServiceTokenProvider(
                WebClient.builder().exchangeFunction(intercambio).build(), props);
    }

    private static ClientResponse ok(String token, long expiresIn) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"accessToken\":\"" + token + "\",\"expiresIn\":" + expiresIn + "}")
                .build();
    }

    @Test
    void obtieneElToken_conSuPropioClientYSecret() {
        var p = provider("un-secreto", () -> ok("token-abc", 900));

        assertThat(p.token()).contains("token-abc");
    }

    @Test
    void cachea_yNoPideUnoNuevoPorCadaResolucion() {
        var p = provider("un-secreto", () -> ok("token-abc", 900));

        p.token();
        p.token();
        p.token();

        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void tokenYaCaducado_pideOtro() {
        // expiresIn menor que el margen de renovación: nace inservible, así que cada consulta
        // vuelve a pedir en vez de devolver algo que el servidor va a rechazar.
        var p = provider("un-secreto", () -> ok("token-corto", 1));

        p.token();
        p.token();

        assertThat(llamadas.get()).isEqualTo(2);
    }

    @Test
    void sinSecretoConfigurado_devuelveVacioSinLlamar() {
        var p = provider(null, () -> ok("no-deberia-pedirse", 900));

        assertThat(p.token()).isEmpty();
        assertThat(llamadas.get()).isZero();
    }

    @Test
    void secretoRechazado_noLanzaYDevuelveVacio() {
        var p = provider("mal-secreto", () -> ClientResponse.create(HttpStatus.UNAUTHORIZED)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"error\":\"invalid_client\"}").build());

        assertThat(p.token()).isEmpty();
    }

    @Test
    void identityCaido_noLanzaYNoInsisteEnCadaRequest() {
        var p = provider("un-secreto", () -> ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body("{}").build());

        assertThat(p.token()).isEmpty();
        assertThat(p.token()).isEmpty();
        assertThat(p.token()).isEmpty();

        // Un servicio caído no se arregla insistiendo: tras el fallo se espera antes de reintentar.
        assertThat(llamadas.get()).isEqualTo(1);
    }

    @Test
    void respuestaSinAccessToken_seTrataComoFallo() {
        var p = provider("un-secreto", () -> ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"expiresIn\":900}").build());

        assertThat(p.token()).isEmpty();
    }

    @Test
    void invalidate_obligaAPedirUnoNuevo() {
        var p = provider("un-secreto", () -> ok("token-abc", 900));

        p.token();
        p.invalidate();
        p.token();

        assertThat(llamadas.get()).isEqualTo(2);
    }
}
