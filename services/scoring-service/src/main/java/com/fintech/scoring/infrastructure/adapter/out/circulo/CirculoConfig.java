package com.fintech.scoring.infrastructure.adapter.out.circulo;

import java.net.http.HttpClient;

import com.fintech.scoring.infrastructure.config.CirculoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class CirculoConfig {

    /**
     * El cliente del buró, <b>con límites de tiempo</b>.
     *
     * <p>Antes se armaba sin fábrica de requests: sin timeout de conexión ni de lectura. Un buró que
     * acepta la conexión y no contesta bloqueaba el listener de {@code prospect-created} sin tope, y
     * con él el alta de todos los que venían detrás. Ahora la espera es la de la configuración y,
     * vencida, el adaptador la trata como falla transitoria: se reintenta con backoff.
     *
     * <p>HTTP/1.1 explícito: el buró no habla HTTP/2 en claro y el {@code HttpClient} del JDK
     * intentaría negociarlo.
     */
    @Bean
    RestClient circuloRestClient(CirculoProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.getReadTimeout());
        return RestClient.builder()
                .baseUrl(props.getUrl())
                .requestFactory(factory)
                .defaultHeader("x-api-key", props.getApiKey() == null ? "" : props.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
