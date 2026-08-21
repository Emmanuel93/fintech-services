package com.fintech.channelmobile.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class IdentityClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    private final WebClient webClient;

    public IdentityClient(@Qualifier("identityWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public TokenResponse login(String username, String password, String deviceId) {
        log.info("-> POST identity-service /api/v1/auth/login username={}", username);
        Map<String, Object> body = buildLoginBody(username, password, deviceId);

        return webClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(TokenResponse.class)
                .doOnNext(t -> log.info("<- identity-service login OK username={}", username))
                .block();
    }

    public TokenResponse refresh(String refreshToken) {
        log.info("-> POST identity-service /api/v1/auth/refresh");
        return webClient.post()
                .uri("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("refreshToken", refreshToken))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .bodyToMono(TokenResponse.class)
                .doOnNext(t -> log.info("<- identity-service refresh OK"))
                .block();
    }

    public void logout(String bearerToken) {
        log.info("-> POST identity-service /api/v1/auth/logout");
        webClient.post()
                .uri("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .onStatus(s -> s != HttpStatus.NO_CONTENT && s.isError(), resp ->
                        resp.bodyToMono(String.class).flatMap(detail ->
                                Mono.error(new ResponseStatusException(resp.statusCode(), detail))))
                .toBodilessEntity()
                .doOnNext(r -> log.info("<- identity-service logout OK"))
                .block();
    }

    private Map<String, Object> buildLoginBody(String username, String password, String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            return Map.of("username", username, "password", password, "deviceId", deviceId);
        }
        return Map.of("username", username, "password", password);
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            String tokenType
    ) {}
}
