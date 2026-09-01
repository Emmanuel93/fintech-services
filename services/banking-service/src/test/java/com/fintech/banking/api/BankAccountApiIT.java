package com.fintech.banking.api;

import com.fintech.banking.domain.Clabe;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterios de aceptación — alta y consulta de cuentas propias (BK-05).
 *
 * <pre>
 * AC-1  POST sin token                          → 401
 * AC-2  POST con rol insuficiente               → 403
 * AC-3  POST como ADMIN                         → 201 y la CLABE sale enmascarada
 * AC-4  POST con CLABE inválida                 → 400 antes de tocar la base
 * AC-5  POST con CLABE ya dada de alta          → 409 con motivo legible
 * AC-6  GET  de una cuenta inexistente          → 404
 * AC-7  El alta trae las DOS puentes por default
 * AC-8  Suspender saca del ruteo sin borrar
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BankAccountApiIT extends AbstractIntegrationTest {

    @Override protected List<String> esquemas() { return List.of("banking"); }

    /**
     * El seed de ambiente bajo sobrevive al truncado. Sin esto, la primera prueba borra la cuenta y
     * la ruta que sembró Liquibase y a partir de ahí todo falla con «sin ruta» — el síntoma
     * desconcertante que documenta {@code AbstractIntegrationTest#preservar()}.
     */
    @Override protected List<String> preservar() { return List.of("bank_accounts", "payout_routes"); }

    @Autowired TestRestTemplate rest;

    /**
     * Una CLABE válida distinta en cada llamada; el dígito verificador se calcula, no se inventa.
     *
     * <p>Aleatoria y no un contador: el contenedor de Postgres se reusa entre corridas, así que un
     * contador que arranca en cero volvería a chocar con las CLABEs de la corrida anterior.
     */
    private static String clabeValida() {
        String base = "012180" + String.format("%011d",
                java.util.concurrent.ThreadLocalRandom.current().nextLong(100_000_000_000L));
        return base + Clabe.digitoVerificador(base);
    }

    private static HttpHeaders comoRol(String... roles) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-User-Id", "tester");
        h.set("X-Roles", String.join(",", roles));
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return h;
    }

    private static Map<String, Object> alta(String clabe) {
        return Map.of("institutionName", "BBVA",
                      "clabe", clabe,
                      "holderName", "FINTECH SA DE CV");
    }

    private ResponseEntity<Map> postAlta(HttpHeaders headers, Map<String, Object> cuerpo) {
        return rest.exchange("/api/v1/bank-accounts", HttpMethod.POST,
                new HttpEntity<>(cuerpo, headers), Map.class);
    }

    @Test
    @DisplayName("AC-1 · sin token no se da de alta una cuenta ordenante")
    void sinToken() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(postAlta(h, alta(clabeValida())).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AC-2 · consultar no habilita a dar de alta")
    void rolInsuficiente() {
        // Quien puede dar de alta una CLABE ordenante puede después apuntarle una ruta y hacer que
        // el dinero salga por ella. Es el permiso más sensible del servicio.
        assertThat(postAlta(comoRol("AUDITOR"), alta(clabeValida())).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("AC-3 y AC-7 · el alta responde 201, enmascara la CLABE y trae las dos puentes")
    void altaComoAdmin() {
        String clabe = clabeValida();
        ResponseEntity<Map> r = postAlta(comoRol("ADMIN"), alta(clabe));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> cuerpo = r.getBody();
        assertThat(cuerpo).isNotNull();
        // La respuesta NO devuelve la CLABE completa: una pantalla que la muestra se fotografía.
        assertThat(cuerpo.get("clabe")).isEqualTo("****" + clabe.substring(14));
        assertThat(cuerpo.get("status")).isEqualTo("ACTIVE");
        // Defaults del catálogo contable (`accounting:011-bank-suspense`).
        assertThat(cuerpo.get("ledgerAccount")).isEqualTo("1101");
        assertThat(cuerpo.get("suspenseCreditAccount")).isEqualTo("2109");
        assertThat(cuerpo.get("suspenseDebitAccount")).isEqualTo("1109");
        // La institución se deduce de la propia CLABE cuando no se manda.
        assertThat(cuerpo.get("institutionCode")).isEqualTo("012");
    }

    @Test
    @DisplayName("AC-4 · una CLABE con el verificador mal rebota con 400")
    void clabeInvalida() {
        String buena = clabeValida();
        String mala  = buena.substring(0, 17) + ((buena.charAt(17) - '0' + 1) % 10);

        ResponseEntity<Map> r = postAlta(comoRol("ADMIN"), alta(mala));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Y no quedó rastro: se valida antes de tocar la base.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM banking.bank_accounts WHERE clabe = ?", Integer.class, mala))
                .isZero();
    }

    @Test
    @DisplayName("AC-5 · la misma CLABE dos veces da 409 con motivo, no una violación de restricción")
    void clabeDuplicada() {
        String clabe = clabeValida();
        assertThat(postAlta(comoRol("ADMIN"), alta(clabe)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> segunda = postAlta(comoRol("ADMIN"), alta(clabe));
        assertThat(segunda.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(segunda.getBody().get("detail")))
                .contains("ya está dada de alta")
                // Ni siquiera el mensaje de error lleva la CLABE completa.
                .doesNotContain(clabe);
    }

    @Test
    @DisplayName("AC-6 · una cuenta que no existe da 404")
    void noExiste() {
        ResponseEntity<Map> r = rest.exchange("/api/v1/bank-accounts/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(comoRol("ADMIN")), Map.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AC-8 · suspender saca del ruteo y la cuenta sigue consultable")
    void suspender() {
        Map<?, ?> creada = postAlta(comoRol("ADMIN"), alta(clabeValida())).getBody();
        String id = String.valueOf(creada.get("bankAccountId"));

        ResponseEntity<Map> s = rest.exchange("/api/v1/bank-accounts/" + id + "/suspend",
                HttpMethod.POST, new HttpEntity<>(comoRol("ADMIN")), Map.class);
        assertThat(s.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(s.getBody().get("status")).isEqualTo("SUSPENDED");

        // Lo que ya salió por ella se sigue conciliando: la cuenta no desaparece.
        ResponseEntity<Map> g = rest.exchange("/api/v1/bank-accounts/" + id,
                HttpMethod.GET, new HttpEntity<>(comoRol("AUDITOR")), Map.class);
        assertThat(g.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(g.getBody().get("status")).isEqualTo("SUSPENDED");
    }
}
