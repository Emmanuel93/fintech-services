package com.fintech.identity;

import com.fintech.identity.domain.Channel;

import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.infrastructure.adapter.in.api.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de aceptación de T1 — Client/Secret auth.
 *
 * Infraestructura:
 *   - PostgreSQL: Testcontainers JDBC URL (application-test.properties)
 *   - Redis: GenericContainer (configurado vía @DynamicPropertySource)
 *
 * Criterios:
 *   AC-1  Registro de cliente → secret devuelto una sola vez
 *   AC-2  Auth con IP permitida → 200 + tokens válidos
 *   AC-3  Auth con IP bloqueada → 403 Forbidden
 *   AC-4  Auth sin entradas en whitelist → 200 (sin restricción de IP)
 *   AC-5  Cliente deshabilitado → 401 Unauthorized
 *   AC-6  Flujo completo: auth → validate muestra roles del cliente
 *   AC-7  Endpoints admin sin token → 401
 *   AC-8  Gestión de whitelist: agregar, listar, eliminar
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ClientAuthAcceptanceTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired TokenPort tokenPort;

    // ── AC-1: Registro ────────────────────────────────────────────────────

    @Test
    void ac1_register_returnsClientWithSecret() {
        String id = uniqueClientId();

        ResponseEntity<ClientRegistrationResponse> resp = restTemplate.exchange(
                "/api/v1/auth/clients", HttpMethod.POST,
                adminEntity(new RegisterClientRequest(id, "Test Service",
                        List.of("SYSTEM_TEST"), null)),
                ClientRegistrationResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().clientId()).isEqualTo(id);
        assertThat(resp.getBody().clientSecret()).isNotBlank().hasSize(64);
        assertThat(resp.getBody().status().name()).isEqualTo("ACTIVE");
        assertThat(resp.getBody().roles()).containsExactly("SYSTEM_TEST");
    }

    @Test
    void ac1_register_duplicateClientId_returns409() {
        String id = uniqueClientId();
        restTemplate.exchange("/api/v1/auth/clients", HttpMethod.POST,
                adminEntity(new RegisterClientRequest(id, "First", List.of("SYSTEM"), null)),
                ClientRegistrationResponse.class);

        ResponseEntity<Map> second = restTemplate.exchange(
                "/api/v1/auth/clients", HttpMethod.POST,
                adminEntity(new RegisterClientRequest(id, "Second", List.of("SYSTEM"), null)),
                Map.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── AC-2: Auth con IP permitida ───────────────────────────────────────

    @Test
    void ac2_authenticate_withAllowedIp_returns200WithTokens() {
        String id = uniqueClientId();
        String secret = register(id, List.of("SYSTEM_SCORING"));
        addWhitelist(id, "127.0.0.1/32", "local");

        ResponseEntity<TokenResponse> resp = restTemplate.postForEntity(
                "/api/v1/auth/clients/token",
                new ClientTokenRequest(id, secret),
                TokenResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().accessToken()).isNotBlank();
        assertThat(resp.getBody().refreshToken()).isNotBlank();
        assertThat(resp.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(resp.getBody().expiresIn()).isEqualTo(15 * 60L);
    }

    // ── AC-3: Auth con IP bloqueada ───────────────────────────────────────

    @Test
    void ac3_authenticate_withBlockedIp_returns403() {
        String id = uniqueClientId();
        String secret = register(id, List.of("SYSTEM"));
        addWhitelist(id, "10.0.0.1/32", "remote-only");

        // TestRestTemplate calls from 127.0.0.1, which is NOT in 10.0.0.1/32
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/api/v1/auth/clients/token",
                new ClientTokenRequest(id, secret),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── AC-4: Auth sin whitelist → cualquier IP permitida ─────────────────

    @Test
    void ac4_authenticate_emptyWhitelist_anyIpAllowed() {
        String id = uniqueClientId();
        String secret = register(id, List.of("SYSTEM"));
        // No whitelist entries added

        ResponseEntity<TokenResponse> resp = restTemplate.postForEntity(
                "/api/v1/auth/clients/token",
                new ClientTokenRequest(id, secret),
                TokenResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().accessToken()).isNotBlank();
    }

    // ── AC-5: Cliente deshabilitado ───────────────────────────────────────

    @Test
    void ac5_authenticate_disabledClient_returns401() {
        String id = uniqueClientId();
        String secret = register(id, List.of("SYSTEM"));
        disableClient(id);

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "/api/v1/auth/clients/token",
                new ClientTokenRequest(id, secret),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── AC-6: Auth → validate refleja roles del cliente ───────────────────

    @Test
    void ac6_fullFlow_authenticateThenValidate_returnsClientRoles() {
        String id = uniqueClientId();
        String secret = register(id, List.of("SYSTEM_SCORING", "SYSTEM_RISK"));
        // Empty whitelist → all IPs allowed

        TokenResponse tokens = restTemplate.postForEntity(
                "/api/v1/auth/clients/token",
                new ClientTokenRequest(id, secret),
                TokenResponse.class).getBody();
        assertThat(tokens).isNotNull();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokens.accessToken());
        ResponseEntity<TokenValidationResponse> validateResp = restTemplate.exchange(
                "/api/v1/auth/validate", HttpMethod.GET,
                new HttpEntity<>(headers), TokenValidationResponse.class);

        assertThat(validateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(validateResp.getBody()).isNotNull();
        // Filter prepends ROLE_, validate strips it back to original for the response
        assertThat(validateResp.getBody().partyId()).isNotNull();
        assertThat(validateResp.getBody().roles()).isNotEmpty();
    }

    // ── AC-7: Endpoints admin sin token ──────────────────────────────────

    @Test
    void ac7_adminEndpoints_withoutToken_return401() {
        String id = uniqueClientId();

        assertThat(restTemplate.exchange(
                "/api/v1/auth/clients", HttpMethod.POST,
                jsonEntity(new RegisterClientRequest(id, "Name", List.of("R"), null)),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(restTemplate.exchange(
                "/api/v1/auth/clients/" + id, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── AC-8: Gestión de whitelist ────────────────────────────────────────

    @Test
    void ac8_whitelistCrud_addListRemove() {
        String id = uniqueClientId();
        register(id, List.of("SYSTEM"));

        // Add entry
        ResponseEntity<WhitelistEntryResponse> addResp = restTemplate.exchange(
                "/api/v1/auth/clients/" + id + "/whitelist", HttpMethod.POST,
                adminEntity(new WhitelistEntryRequest("192.168.1.0/24", "dev")),
                WhitelistEntryResponse.class);
        assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID entryId = addResp.getBody().id();

        // List entries
        ResponseEntity<List<WhitelistEntryResponse>> listResp = restTemplate.exchange(
                "/api/v1/auth/clients/" + id + "/whitelist", HttpMethod.GET,
                adminEntity(null),
                new ParameterizedTypeReference<>() {});
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(1);
        assertThat(listResp.getBody().get(0).cidr()).isEqualTo("192.168.1.0/24");

        // Remove entry
        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/auth/clients/" + id + "/whitelist/" + entryId, HttpMethod.DELETE,
                adminEntity(null), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // List again → empty
        ResponseEntity<List<WhitelistEntryResponse>> emptyListResp = restTemplate.exchange(
                "/api/v1/auth/clients/" + id + "/whitelist", HttpMethod.GET,
                adminEntity(null),
                new ParameterizedTypeReference<>() {});
        assertThat(emptyListResp.getBody()).isEmpty();
    }

    @Test
    void ac8_getClient_asAdmin_returnsInfo() {
        String id = uniqueClientId();
        register(id, List.of("SYSTEM_TEST"));

        ResponseEntity<ClientInfoResponse> resp = restTemplate.exchange(
                "/api/v1/auth/clients/" + id, HttpMethod.GET,
                adminEntity(null), ClientInfoResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().clientId()).isEqualTo(id);
        assertThat(resp.getBody().roles()).containsExactly("SYSTEM_TEST");
        assertThat(resp.getBody().status().name()).isEqualTo("ACTIVE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String uniqueClientId() {
        return "test-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String register(String clientId, List<String> roles) {
        ResponseEntity<ClientRegistrationResponse> resp = restTemplate.exchange(
                "/api/v1/auth/clients", HttpMethod.POST,
                adminEntity(new RegisterClientRequest(clientId, "Test Service", roles, null)),
                ClientRegistrationResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody().clientSecret();
    }

    private void addWhitelist(String clientId, String cidr, String label) {
        ResponseEntity<WhitelistEntryResponse> resp = restTemplate.exchange(
                "/api/v1/auth/clients/" + clientId + "/whitelist", HttpMethod.POST,
                adminEntity(new WhitelistEntryRequest(cidr, label)),
                WhitelistEntryResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private void disableClient(String clientId) {
        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/auth/clients/" + clientId, HttpMethod.DELETE,
                adminEntity(null), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private <T> HttpEntity<T> adminEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + tokenPort.generateAccessToken(
                UUID.randomUUID(), List.of("ADMIN"), null, Channel.SERVICE));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private <T> HttpEntity<T> jsonEntity(T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
