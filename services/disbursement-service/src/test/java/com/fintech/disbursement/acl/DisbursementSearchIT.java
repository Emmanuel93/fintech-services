package com.fintech.disbursement.acl;

import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedListener;
import com.fintech.disbursement.infrastructure.adapter.in.messaging.DispositionAuthorizedPayload;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La consulta de tesorería sobre los pagos: por crédito, por beneficiario o por clave de rastreo,
 * del más nuevo al más viejo, con su total — contra la base real.
 *
 * <p>El crédito vive en el eco opaco de {@code source_metadata}: es la parte que una consulta JPQL
 * no alcanza y la que hay que ver funcionar en Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DisbursementSearchIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("disbursement"); }

    /** El mapeo de empresa lo siembra Liquibase; sin él DB-07 rechaza toda orden. */
    @Override protected List<String> preserve() { return List.of("company_mappings"); }

    @Autowired DispositionAuthorizedListener listener;
    @Autowired TestRestTemplate rest;

    private UUID pago(UUID creditAccountId, String beneficiario, String importe, Instant creado, String clave) {
        UUID disposition = UUID.randomUUID();
        listener.onDispositionAuthorized(new DispositionAuthorizedPayload(
                disposition, creditAccountId, null, "SUC-001", "THIRD_PARTY_CREDIT",
                new BigDecimal(importe), "MXN", beneficiario, "646180157000000004", "40", null, 646,
                1L, "DISPOSICION DE CREDITO", null, null, Instant.now()));
        UUID id = jdbcTemplate.queryForObject(
                "SELECT disbursement_id FROM disbursement.disbursement_orders WHERE source_event_id = ?",
                UUID.class, disposition.toString());
        // La fecha y la clave se fijan aquí para que el orden y la búsqueda no dependan del reloj.
        jdbcTemplate.update("UPDATE disbursement.disbursement_orders SET created_at = ?, external_ref = ? "
                + "WHERE disbursement_id = ?", Timestamp.from(creado), clave, id);
        return id;
    }

    private static HttpHeaders como(String rol) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", "tesoreria-1");
        h.set("X-Roles", rol);
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> buscar(String query, String rol, Object... vars) {
        return rest.exchange("/api/v1/disbursements/search" + query, HttpMethod.GET,
                new HttpEntity<>(como(rol)), Map.class, vars);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filas(ResponseEntity<Map> r) {
        return (List<Map<String, Object>>) r.getBody().get("content");
    }

    @Test
    @DisplayName("por crédito: sólo los pagos de esa cuenta, del más nuevo al más viejo, con su total")
    void porCredito() {
        UUID credito = UUID.randomUUID(), otro = UUID.randomUUID();
        UUID viejo = pago(credito, "ROSA ELENA IBARRA SALGADO", "45000.00", Instant.parse("2026-10-01T10:00:00Z"), "KRD-A");
        UUID nuevo = pago(credito, "MARIA LUISA CORTES", "1000.00", Instant.parse("2026-10-30T10:00:00Z"), "KRD-B");
        pago(otro, "JUAN PEREZ", "2000.00", Instant.parse("2026-10-31T10:00:00Z"), "KRD-C");

        var r = buscar("?creditAccountId=" + credito, "FINANCE");

        // Tesorería (FINANCE) consulta: antes este GET era sólo ADMIN/OPS_SUPERVISOR/AUDITOR.
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(filas(r)).extracting(f -> f.get("disbursementId"))
                .containsExactly(nuevo.toString(), viejo.toString());
        assertThat(r.getBody().get("totalElements")).isEqualTo(2);
        assertThat(r.getBody().get("totalPages")).isEqualTo(1);
        assertThat(filas(r).get(0).get("beneficiaryAccountMasked")).isEqualTo("****0004");
    }

    @Test
    @DisplayName("q busca en el nombre del beneficiario y en la clave de rastreo, sin distinguir mayúsculas")
    void porTexto() {
        UUID credito = UUID.randomUUID();
        UUID rosa = pago(credito, "ROSA ELENA IBARRA SALGADO", "45000.00", Instant.parse("2026-10-01T10:00:00Z"), "KRD2026100100000000000001");
        UUID juan = pago(credito, "JUAN PEREZ", "1000.00", Instant.parse("2026-10-02T10:00:00Z"), "KRD2026100200000000000777");

        assertThat(filas(buscar("?q=ibarra", "FINANCE"))).extracting(f -> f.get("disbursementId"))
                .containsExactly(rosa.toString());
        assertThat(filas(buscar("?q=0777", "FINANCE"))).extracting(f -> f.get("disbursementId"))
                .containsExactly(juan.toString());
        // Un comodín escrito por el operador es texto, no «cualquier cosa».
        assertThat(filas(buscar("?q={q}", "FINANCE", "%"))).isEmpty();
        assertThat(filas(buscar("?q={q}", "FINANCE", "_"))).isEmpty();
    }

    @Test
    @DisplayName("pagina: el total no depende de la página pedida")
    void pagina() {
        UUID credito = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            pago(credito, "BENEFICIARIA " + i, "100.00", Instant.parse("2026-10-0" + (i + 1) + "T10:00:00Z"), "KRD-P" + i);
        }

        var r = buscar("?creditAccountId=" + credito + "&page=1&size=2", "OPS_SUPERVISOR");

        assertThat(filas(r)).extracting(f -> f.get("beneficiaryName"))
                .containsExactly("BENEFICIARIA 2", "BENEFICIARIA 1");
        assertThat(r.getBody().get("totalElements")).isEqualTo(5);
        assertThat(r.getBody().get("totalPages")).isEqualTo(3);
        assertThat(r.getBody().get("page")).isEqualTo(1);
    }

    @Test
    @DisplayName("un rol sin consulta de pagos no busca")
    void sinRolNoBusca() {
        assertThat(buscar("", "SUPPORT").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("búsquedas concurrentes mientras entran pagos: cada respuesta es una página coherente")
    void busquedasConcurrentes() throws Exception {
        UUID credito = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            pago(credito, "BASE " + i, "100.00", Instant.parse("2026-09-0" + (i + 1) + "T10:00:00Z"), "KRD-BASE" + i);
        }
        int hilos = 6;
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(pool.submit(() -> {
                salida.await();
                return buscar("?creditAccountId=" + credito + "&size=10", "FINANCE");
            }));
        }
        salida.countDown();
        for (var t : tareas) {
            var r = t.get(30, TimeUnit.SECONDS);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(filas(r)).hasSize(4);
            assertThat(r.getBody().get("totalElements")).isEqualTo(4);
        }
        pool.shutdown();
    }
}
