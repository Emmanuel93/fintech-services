package com.fintech.channelbackoffice.infrastructure.adapter.out.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Acceso a identity-service: sesión del personal y administración del directorio. */
@Component
public class IdentityClient {

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    private final WebClient webClient;

    public IdentityClient(@Qualifier("identityWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    // ── Sesión ────────────────────────────────────────────────────────────

    public StaffSessionResponse staffLogin(String email, String password,
                                           String forwardedFor, String userAgent) {
        log.info("-> POST identity-service /api/v1/auth/staff/login email={}", email);
        return webClient.post()
                .uri("/api/v1/auth/staff/login")
                .headers(h -> {
                    // identity resuelve la IP del cliente desde estos headers para su bitácora;
                    // sin reenviarlos registraría la IP del BFF en cada login.
                    if (forwardedFor != null) h.set("X-Forwarded-For", forwardedFor);
                    if (userAgent != null) h.set(HttpHeaders.USER_AGENT, userAgent);
                })
                .bodyValue(Map.of("email", email, "password", password))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffSessionResponse.class)
                .doOnNext(r -> log.info("<- identity-service 200 staffUserId={}", r.user().staffUserId()))
                .block();
    }

    public StaffSessionResponse staffRefresh(String refreshToken) {
        log.info("-> POST identity-service /api/v1/auth/staff/refresh");
        return webClient.post()
                .uri("/api/v1/auth/staff/refresh")
                .bodyValue(Map.of("refreshToken", refreshToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffSessionResponse.class)
                .block();
    }

    public void staffLogout(String bearerToken) {
        log.info("-> POST identity-service /api/v1/auth/staff/logout");
        webClient.post()
                .uri("/api/v1/auth/staff/logout")
                .headers(h -> h.setBearerAuth(bearerToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .toBodilessEntity()
                .block();
    }

    public StaffProfileResponse staffMe(String bearerToken) {
        return webClient.get()
                .uri("/api/v1/auth/staff/me")
                .headers(h -> h.setBearerAuth(bearerToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffProfileResponse.class)
                .block();
    }

    // ── Directorio ────────────────────────────────────────────────────────
    // Passthrough con el token del solicitante: identity vuelve a exigir ADMIN, así que la
    // autorización se decide una sola vez y en el dueño del dato.

    public List<StaffUserResponse> listStaff(String bearerToken, String status, String role) {
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/staff")
                        .queryParamIfPresent("status", java.util.Optional.ofNullable(status))
                        .queryParamIfPresent("role", java.util.Optional.ofNullable(role))
                        .build())
                .headers(h -> h.setBearerAuth(bearerToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToFlux(StaffUserResponse.class)
                .collectList()
                .block();
    }

    /** Ejecutivos de cuenta activos (id + nombre) para selectores. Endpoint no-admin. */
    /**
     * De quién es un usuario de acceso (un teléfono). `null` si nadie lo tiene.
     *
     * La bitácora anota el actor con el que se entró; para saber de quién se trata hay
     * que pasar por identity, que es quien guarda esa correspondencia.
     */
    public CredentialOwnerResponse lookupCredential(String bearerToken, String username) {
        log.info("-> GET identity /auth/credentials/lookup");
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/auth/credentials/lookup")
                        .queryParam("username", username).build())
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .onStatus(org.springframework.http.HttpStatusCode::is4xxClientError, r -> Mono.empty())
                .bodyToMono(CredentialOwnerResponse.class)
                .onErrorResume(e -> Mono.empty())
                .block();
    }

    /** Dueño de una credencial de acceso. Sin secretos. */
    public record CredentialOwnerResponse(
            UUID partyId, String username, String credentialType, String status, Instant lastLoginAt) {}

    /**
     * El catálogo de roles con sus capacidades. Sin token: se llama al arrancar el canal, cuando
     * todavía no hay ninguna sesión de la cual tomarlo.
     */
    public List<RoleResponse> listRoles() {
        log.info("-> GET identity /roles");
        return webClient.get()
                .uri("/api/v1/roles")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<RoleResponse>>() {})
                .block();
    }

    /** Reemplaza las capacidades de un rol. Va con el token de quien lo cambia: identity exige ADMIN. */
    public RoleResponse replaceRoleCapabilities(String bearerToken, String code, Set<String> capabilities) {
        log.info("-> PUT identity /roles/{}/capabilities ({} capacidades)", code, capabilities.size());
        return webClient.put()
                .uri("/api/v1/roles/{code}/capabilities", code)
                .header("Authorization", "Bearer " + bearerToken)
                .bodyValue(Map.of("capabilities", capabilities))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(RoleResponse.class)
                .block();
    }

    /** Un rol del backoffice tal como lo publica identity. */
    public record RoleResponse(
            String code, String name, String description, String channel,
            boolean systemManaged, Set<String> capabilities, Instant updatedAt) {}

    /**
     * Nombres de todo el personal activo. Lo puede pedir cualquier empleado autenticado.
     *
     * <p>Existe porque el directorio completo ({@link #listStaff}) es de ADMIN, y quien mira la
     * estructura comercial es justamente quien no lo es: pedirle nombres por esa vía devolvía 403
     * y la pantalla acababa mostrando UUIDs sin decir por qué.
     */
    public List<ExecutiveResponse> listStaffNames(String bearerToken) {
        log.info("-> GET identity /executives/staff");
        return webClient.get()
                .uri("/api/v1/executives/staff")
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(new ParameterizedTypeReference<List<ExecutiveResponse>>() {})
                .block();
    }

    public List<ExecutiveResponse> listExecutives(String bearerToken) {
        return webClient.get()
                .uri("/api/v1/executives")
                .headers(h -> h.setBearerAuth(bearerToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToFlux(ExecutiveResponse.class)
                .collectList()
                .block();
    }

    public StaffUserResponse getStaff(String bearerToken, UUID staffUserId) {
        return webClient.get()
                .uri("/api/v1/staff/{id}", staffUserId)
                .headers(h -> h.setBearerAuth(bearerToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffUserResponse.class)
                .block();
    }

    public StaffUserResponse createStaff(String bearerToken, Object request) {
        log.info("-> POST identity-service /api/v1/staff");
        return webClient.post()
                .uri("/api/v1/staff")
                .headers(h -> h.setBearerAuth(bearerToken))
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffUserResponse.class)
                .block();
    }

    public StaffUserResponse changeRoles(String bearerToken, UUID staffUserId, Set<String> roles) {
        return put(bearerToken, "/api/v1/staff/{id}/roles", staffUserId, Map.of("roles", roles));
    }

    public StaffUserResponse changePassword(String bearerToken, UUID staffUserId, String password) {
        return put(bearerToken, "/api/v1/staff/{id}/password", staffUserId, Map.of("password", password));
    }

    public StaffUserResponse suspend(String bearerToken, UUID staffUserId) {
        return put(bearerToken, "/api/v1/staff/{id}/suspend", staffUserId, null);
    }

    public StaffUserResponse reactivate(String bearerToken, UUID staffUserId) {
        return put(bearerToken, "/api/v1/staff/{id}/reactivate", staffUserId, null);
    }

    public StaffUserResponse disable(String bearerToken, UUID staffUserId) {
        log.info("-> DELETE identity-service /api/v1/staff/{}", staffUserId);
        return webClient.delete()
                .uri("/api/v1/staff/{id}", staffUserId)
                .headers(h -> h.setBearerAuth(bearerToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffUserResponse.class)
                .block();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private StaffUserResponse put(String bearerToken, String path, UUID staffUserId, Object body) {
        log.info("-> PUT identity-service {} id={}", path, staffUserId);
        var spec = webClient.put()
                .uri(path, staffUserId)
                .headers(h -> h.setBearerAuth(bearerToken));
        return (body == null ? spec.retrieve() : spec.bodyValue(body).retrieve())
                .onStatus(HttpStatusCode::isError, r -> DomainClientSupport.propagate("identity-service", r))
                .bodyToMono(StaffUserResponse.class)
                .block();
    }

    // ── Contratos ─────────────────────────────────────────────────────────

    public record StaffSessionResponse(
            String accessToken,
            String refreshToken,
            long expiresIn,
            String channel,
            StaffProfileResponse user
    ) {}

    public record StaffProfileResponse(
            UUID staffUserId,
            String email,
            String fullName,
            String employeeType,
            UUID distributorPartyId,
            List<String> roles
    ) {}

    public record StaffUserResponse(
            UUID staffUserId,
            String email,
            String fullName,
            String curp,
            String employeeType,
            UUID distributorPartyId,
            List<String> roles,
            String status,
            Instant lastLoginAt,
            Instant createdAt
    ) {}

    /** Selector de ejecutivos (id + nombre) — shared-types Executive. */
    public record ExecutiveResponse(UUID id, String name) {}
}
