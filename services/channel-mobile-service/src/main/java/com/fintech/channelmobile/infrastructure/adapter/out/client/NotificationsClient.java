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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationsClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationsClient.class);

    private static final ParameterizedTypeReference<List<NotificationRecordResponse>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public NotificationsClient(@Qualifier("notificationsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    public List<NotificationRecordResponse> getHistory(UUID partyId, String userId) {
        log.info("-> GET notifications-service /api/v1/notifications/history/{}", partyId);
        return webClient.get()
                .uri("/api/v1/notifications/history/{partyId}", partyId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "notifications-service error: " + resp.statusCode())))
                .bodyToMono(LIST_TYPE)
                .block();
    }

    /** 404 (notificationId no existe) se propaga como ResponseStatusException, igual que el resto de clients. */
    public void markAsRead(UUID notificationId, String userId) {
        log.info("-> PUT notifications-service /api/v1/notifications/{}/read", notificationId);
        webClient.put()
                .uri("/api/v1/notifications/{notificationId}/read", notificationId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "notifications-service error: " + resp.statusCode())))
                .toBodilessEntity()
                .block();
    }

    public void markAllAsRead(UUID recipientId, String userId) {
        log.info("-> PUT notifications-service /api/v1/notifications/read-all/{}", recipientId);
        webClient.put()
                .uri("/api/v1/notifications/read-all/{recipientId}", recipientId)
                .header("X-User-Id", userId)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        Mono.error(new ResponseStatusException(resp.statusCode(),
                                "notifications-service error: " + resp.statusCode())))
                .toBodilessEntity()
                .block();
    }

    /** Alcance v1 del dominio — ver notifications/domain/EventType.java. */
    public record NotificationRecordResponse(
            UUID notificationId,
            UUID recipientId,
            String eventType,
            String channel,
            String status,
            String failureReason,
            Instant sentAt,
            boolean isRead
    ) {}
}
