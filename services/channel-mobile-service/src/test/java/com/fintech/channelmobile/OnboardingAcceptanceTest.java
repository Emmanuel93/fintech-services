package com.fintech.channelmobile;

import com.fintech.channelmobile.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient.ProspectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * Pruebas de aceptación del flujo de onboarding — channel-mobile-service.
 *
 * Principio de seguridad: identity-service es el ÚNICO emisor de tokens.
 * Los endpoints de onboarding (OTP, OCR, KYC, register) son públicos con
 * rate limiting en el gateway. No hay pre-auth tokens intermedios.
 *
 * Infraestructura:
 *   - Redis: Testcontainers
 *   - IdentityClient / OriginationClient: @MockBean
 *
 * Criterios:
 *   AC-1  Flujo completo sin token: OTP → verify → OCR → KYC → register → 201
 *   AC-2  Endpoints de negocio protegidos sin X-User-Id retornan 401
 *   AC-3  Rate limit de OTP: superar el límite retorna 429
 *   AC-4  Register con folioKyc inexistente retorna 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class OnboardingAcceptanceTest {

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

    @MockitoBean IdentityClient identityClient;
    @MockitoBean OriginationClient originationClient;

    static final String PHONE = "5512345678";
    static final String DEV_CODE = "123456"; // activo por perfil test (otp-code-validation=false)

    // ── AC-1: Flujo completo de onboarding sin token intermedio ──────────

    @Test
    void ac1_fullOnboardingFlow_happyPath_conPreAuthToken() {
        given(originationClient.registerProspect(any()))
                .willReturn(new ProspectResponse(
                        "PROSPECT-ACCEPT-001", "RAHE930326HSMLRM09", "+52" + PHONE,
                        Instant.now().toString(), Instant.now().plusSeconds(86400).toString(),
                        "Prospecto creado exitosamente"));

        // Paso 1: Enviar OTP
        ResponseEntity<Map<String, Object>> sendResp = postJson("/otp/send", Map.of("phone", PHONE), null);
        assertThat(sendResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sendResp.getBody()).containsEntry("success", true);

        // Paso 2: Verificar OTP — devuelve verified:true y el pase para el tramo del KYC
        ResponseEntity<Map<String, Object>> verifyResp = postJson(
                "/otp/verify", Map.of("phone", PHONE, "code", DEV_CODE), null);
        assertThat(verifyResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verifyResp.getBody()).containsEntry("verified", true);
        assertThat(verifyResp.getBody()).containsKey("preAuthToken");

        // Paso 3: OCR — público, sin token
        ResponseEntity<Map<String, Object>> ocrResp = postJson(
                "/ocr/extract", Map.of("frente", "dGVzdA==", "reverso", "dGVzdA=="), null);
        assertThat(ocrResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ocrResp.getBody()).containsEntry("success", true);

        // Paso 4: KYC — público, sin token
        ResponseEntity<Map<String, Object>> kycResp = postJson("/kyc/submit", kycPayload(PHONE), null);
        assertThat(kycResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String folioKyc = (String) kycResp.getBody().get("folioKyc");
        assertThat(folioKyc).isNotBlank();

        // Paso 5: Register — público, sin token
        ResponseEntity<Map<String, Object>> registerResp = postJson(
                "/auth/register",
                Map.of("password", "SecurePass1!", "folioKyc", folioKyc),
                null);
        assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResp.getBody()).containsEntry("success", true);
        assertThat(registerResp.getBody().get("userId")).isEqualTo("PROSPECT-ACCEPT-001");
    }

    // ── AC-2: Endpoints de negocio sin X-User-Id → 401 ──────────────────
    // Los endpoints de crédito requieren X-User-Id inyectado por el gateway.

    @Test
    void ac2_creditEndpoints_withoutGatewayHeaders_return401() {
        // GET /credit/applications sin X-User-Id header → 401
        ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(
                "/credit/applications",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                new ParameterizedTypeReference<>() {});
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── AC-3: Rate limit de OTP → 429 ────────────────────────────────────

    @Test
    void ac3_otpRateLimit_exceedingMaxSends_returns429() {
        String phone = "5599887766";

        // application-test.yml: otp-max-sends-per-window=3
        postJson("/otp/send", Map.of("phone", phone), null);
        postJson("/otp/resend", Map.of("phone", phone), null);
        postJson("/otp/resend", Map.of("phone", phone), null);

        // Cuarto envío → supera el límite
        ResponseEntity<Map<String, Object>> rateLimited = postJson(
                "/otp/resend", Map.of("phone", phone), null);
        assertThat(rateLimited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    // ── AC-4: Register con folioKyc inexistente → 404 ────────────────────

    @Test
    void ac4_register_unknownFolioKyc_returns404() {
        ResponseEntity<Map<String, Object>> resp = postJson(
                "/auth/register",
                Map.of("password", "SecurePass1!", "folioKyc", "KYC-INEXISTENTE"),
                null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> postJson(String path, Object body, String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return restTemplate.exchange(
                path, HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {});
    }

    private Map<String, Object> kycPayload(String phone) {
        return Map.ofEntries(
                Map.entry("phone", phone),
                Map.entry("nombres", "EMMANUEL"),
                Map.entry("apellidoPaterno", "RAMÍREZ"),
                Map.entry("apellidoMaterno", "HERNÁNDEZ"),
                Map.entry("curp", "RAHE930326HSMLRM09"),
                Map.entry("rfc", "RAHE930326XXX"),
                Map.entry("fechaNacimiento", "1993-03-26"),
                Map.entry("genero", "H"),
                Map.entry("estadoNacimiento", "Sinaloa"),
                Map.entry("email", "test@example.com"),
                Map.entry("calle", "Av. Principal"),
                Map.entry("numeroExterior", "100"),
                Map.entry("colonia", "Centro"),
                Map.entry("municipio", "Culiacán"),
                Map.entry("ciudad", "Culiacán"),
                Map.entry("estado", "Sinaloa"),
                Map.entry("codigoPostal", "80060"),
                Map.entry("aceptaAvisoPrivacidad", true),
                Map.entry("aceptaCirculo", true)
        );
    }
}
