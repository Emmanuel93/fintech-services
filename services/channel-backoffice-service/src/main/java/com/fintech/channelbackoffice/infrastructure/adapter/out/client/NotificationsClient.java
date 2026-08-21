package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

/**
 * Acceso a notifications-service para el buzón del personal.
 *
 * <p>El tipo de destinatario es {@code STAFF} y el id es el {@code staffUserId} de la sesión.
 * notifications no sabe qué significa {@code STAFF} —ni falta que le haga—: es una etiqueta que
 * este canal eligió al registrar a sus empleados, igual que origination eligió {@code PARTY} para
 * los suyos.
 */
@Component
public class NotificationsClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationsClient.class);

    /** La etiqueta con la que este canal inscribe a su gente. */
    public static final String STAFF = "STAFF";

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;

    public NotificationsClient(@Qualifier("notificationsWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Inscribe (o actualiza) al empleado como destinatario.
     *
     * <p>notifications no puede adivinar a quién le habla: alguien tiene que registrarlo, y quien
     * conoce al personal es este canal. Es idempotente, así que llamarlo de más no hace daño.
     */
    public void registerStaff(UUID staffUserId, String fullName, String email) {
        log.info("-> PUT notifications /recipients/STAFF/{}", staffUserId);
        webClient.put()
                .uri("/api/v1/notifications/recipients/{type}/{id}", STAFF, staffUserId)
                .headers(DomainClientSupport.staffIdentity())
                .bodyValue(Map.of(
                        "displayName", fullName == null ? "" : fullName,
                        "email", email == null ? "" : email,
                        // El buzón de la consola no necesita dirección externa: el aviso vive aquí.
                        // Se manda igual el correo para que un aviso importante pueda salir por ahí
                        // si su política lo pide.
                        "locale", "es-MX"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("notifications-service", r))
                .bodyToMono(MAP)
                .block();
    }

    public Map<String, Object> feed(UUID staffUserId, boolean unreadOnly) {
        log.info("-> GET notifications /feed/STAFF/{} unreadOnly={}", staffUserId, unreadOnly);
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/notifications/feed/{type}/{id}")
                        .queryParam("unreadOnly", unreadOnly)
                        .build(STAFF, staffUserId))
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("notifications-service", r))
                .bodyToMono(MAP)
                .block();
    }

    public Map<String, Object> markAllRead(UUID staffUserId) {
        log.info("-> PUT notifications /feed/STAFF/{}/read-all", staffUserId);
        return webClient.put()
                .uri("/api/v1/notifications/feed/{type}/{id}/read-all", STAFF, staffUserId)
                .headers(DomainClientSupport.staffIdentity())
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("notifications-service", r))
                .bodyToMono(MAP)
                .block();
    }
}
