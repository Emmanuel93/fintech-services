package com.fintech.channelmobile;

import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * Diferir una compra y saltar un pago desde la app (BK-25, BK-31).
 *
 * <p>Las dos son decisiones <b>del cliente</b>. Existían en cartera desde el principio, pero
 * ningún canal las exponía: el caso de uso estaba escrito y nadie podía llegar a él.
 *
 * <p>Lo que estas pruebas fijan, y que es de diseño y no de implementación: <b>ninguno de los
 * endpoints recibe el id de la cuenta</b>. Se resuelve del usuario autenticado, de modo que no
 * existe la petición capaz de diferir la compra de otro ni de saltarle el pago. La pertenencia no
 * se comprueba —comprobarla admite olvidarla— sino que se construye.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ParcialidadesYSaltoDePagoAcceptanceTest {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired TestRestTemplate rest;
    @MockitoBean CreditPortfolioClient creditPortfolioClient;

    private static final UUID TITULAR = UUID.randomUUID();
    private static final UUID SU_CUENTA = UUID.randomUUID();

    @BeforeEach
    void elTitularTieneUnaTarjeta() {
        given(creditPortfolioClient.getAccountsByPartyId(eq(TITULAR), any()))
                .willReturn(List.of(cuenta(SU_CUENTA, TITULAR)));
    }

    // ── La pertenencia, que es lo que de verdad se está probando ─────────────

    @Test
    @DisplayName("Diferir actúa sobre la cuenta del que llama, no sobre ninguna que venga en la petición")
    void diferir_usa_la_cuenta_del_usuario_autenticado() {
        UUID compra = UUID.randomUUID();

        ResponseEntity<Void> r = rest.exchange(
                "/credit/dispositions/" + compra + "/defer", HttpMethod.POST,
                new HttpEntity<>(Map.of("termPeriods", 6), comoTitular()), Void.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(creditPortfolioClient).deferDisposition(SU_CUENTA, compra, 6, TITULAR.toString());
    }

    @Test
    @DisplayName("Saltar un pago actúa sobre la cuenta del que llama")
    void saltar_usa_la_cuenta_del_usuario_autenticado() {
        UUID cuota = UUID.randomUUID();

        ResponseEntity<Void> r = rest.exchange(
                "/credit/installments/" + cuota + "/skip", HttpMethod.POST,
                new HttpEntity<>(comoTitular()), Void.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(creditPortfolioClient).skipInstallment(SU_CUENTA, cuota, TITULAR.toString());
    }

    @Test
    @DisplayName("Sin identidad no se difiere nada")
    void sin_identidad_no_hay_diferimiento() {
        ResponseEntity<Void> r = rest.exchange(
                "/credit/dispositions/" + UUID.randomUUID() + "/defer", HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), Void.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── El contrato del plazo ────────────────────────────────────────────────

    @Test
    @DisplayName("Sin cuerpo, el plazo viaja nulo: lo decide el producto, no el BFF")
    void sin_plazo_el_producto_decide() {
        UUID compra = UUID.randomUUID();

        ResponseEntity<Void> r = rest.exchange(
                "/credit/dispositions/" + compra + "/defer", HttpMethod.POST,
                new HttpEntity<>(comoTitular()), Void.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(creditPortfolioClient).deferDisposition(SU_CUENTA, compra, null, TITULAR.toString());
    }

    // ── El no del dominio llega con su motivo ────────────────────────────────

    @Test
    @DisplayName("Fuera de ventana: el 409 del dominio llega como 409, no como fallo genérico")
    void fuera_de_ventana_conserva_el_motivo() {
        UUID compra = UUID.randomUUID();
        willThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "La compra ya pasó por el corte: fuera de la ventana de diferimiento"))
                .given(creditPortfolioClient)
                .deferDisposition(any(), any(), any(), any());

        ResponseEntity<String> r = rest.exchange(
                "/credit/dispositions/" + compra + "/defer", HttpMethod.POST,
                new HttpEntity<>(comoTitular()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Tope de saltos agotado: también es un 409 con motivo")
    void tope_de_saltos_agotado_es_conflicto() {
        willThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                "El ciclo ya usó su único salto"))
                .given(creditPortfolioClient).skipInstallment(any(), any(), any());

        ResponseEntity<String> r = rest.exchange(
                "/credit/installments/" + UUID.randomUUID() + "/skip", HttpMethod.POST,
                new HttpEntity<>(comoTitular()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── Y lo que hace falta para poder elegir ────────────────────────────────

    @Test
    @DisplayName("El titular ve sus compras: sin eso, 'elegir cuál diferir' no significa nada")
    void el_titular_ve_sus_compras() {
        given(creditPortfolioClient.getDispositions(eq(SU_CUENTA), any()))
                .willReturn(List.of(Map.of("dispositionId", UUID.randomUUID().toString(),
                        "amount", 3000, "planMode", "REVOLVING")));

        ResponseEntity<String> r = rest.exchange("/credit/dispositions", HttpMethod.GET,
                new HttpEntity<>(comoTitular()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("REVOLVING");
    }

    @Test
    @DisplayName("El titular ve su calendario: de ahí sale la cuota que decide saltar")
    void el_titular_ve_su_calendario() {
        given(creditPortfolioClient.getSchedule(eq(SU_CUENTA), any()))
                .willReturn(List.of(Map.of("installmentNumber", 2, "status", "PENDING")));

        ResponseEntity<String> r = rest.exchange("/credit/schedule", HttpMethod.GET,
                new HttpEntity<>(comoTitular()), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("installmentNumber");
    }

    @Test
    @DisplayName("Sin cuenta activa no hay nada que diferir: 404, no un 500")
    void sin_cuenta_activa_es_no_encontrado() {
        UUID huerfano = UUID.randomUUID();
        given(creditPortfolioClient.getAccountsByPartyId(eq(huerfano), any())).willReturn(List.of());

        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", huerfano.toString());
        h.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> r = rest.exchange(
                "/credit/dispositions/" + UUID.randomUUID() + "/defer", HttpMethod.POST,
                new HttpEntity<>(h), String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private static HttpHeaders comoTitular() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", TITULAR.toString());
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static CreditAccountResponse cuenta(UUID id, UUID titular) {
        return new CreditAccountResponse(
                id, UUID.randomUUID(), "CT-0001", "CC-IND-STD-V1", "CREDIT_CARD", "REVOLVING",
                titular, "ACTIVE", null, null, null, null, null, null, null, null, null,
                null, null, null, 0, null, null,
                null, null, null, null, null, null, null, null);
    }
}
