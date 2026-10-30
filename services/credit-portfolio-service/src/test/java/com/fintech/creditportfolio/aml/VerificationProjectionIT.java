package com.fintech.creditportfolio.aml;

import com.fintech.creditportfolio.application.port.out.PartyVerificationRepository;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.VerificationCompletedListener;
import com.fintech.creditportfolio.infrastructure.adapter.in.messaging.VerificationCompletedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La proyección del veredicto, contra Postgres real: que la compuerta de disposición encuentre a la
 * persona por la llave que tenga a mano.
 *
 * <p>Esta prueba existe porque la tabla estuvo vacía en la base local con 46 cuentas vivas: todo
 * veredicto llegaba sin partyId y se descartaba, y la cuenta guarda al responsable por su
 * prospectId. Lo que se fija aquí es que eso ya no pueda volver a pasar en silencio.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"compliance.verification-completed"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class VerificationProjectionIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fintech").withUsername("fintech").withPassword("fintech");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("fintech.credit-portfolio.jwt-secret",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo");
    }

    @Autowired VerificationCompletedListener listener;
    @Autowired PartyVerificationRepository verifications;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void limpia() { jdbc.update("DELETE FROM credit_portfolio.party_verifications"); }

    private static VerificationCompletedPayload veredicto(UUID party, UUID prospect, String identidad) {
        boolean pasa = "VERIFIED".equals(identidad);
        return new VerificationCompletedPayload(party, prospect, identidad, "CLEARED", pasa,
                pasa ? null : "identidad " + identidad);
    }

    @Test
    @DisplayName("un veredicto sin partyId ya no se descarta: se encuentra por el prospecto del titular")
    void aVerdictWithoutPartyIsFoundByProspect() {
        UUID prospecto = UUID.randomUUID();

        listener.onVerificationCompleted(veredicto(null, prospecto, "IN_REVIEW"));

        // `obligor_party_id` guarda el prospectId: es con este id con el que pregunta la compuerta.
        assertThat(verifications.findByPartyId(prospecto)).hasValueSatisfying(v ->
                assertThat(v.getIdentityStatus()).isEqualTo("IN_REVIEW"));
    }

    @Test
    @DisplayName("cuando llega el Party se completa la MISMA fila, y se encuentra por las dos llaves")
    void thePartyCompletesTheSameRow() {
        UUID prospecto = UUID.randomUUID();
        UUID party = UUID.randomUUID();

        listener.onVerificationCompleted(veredicto(null, prospecto, "IN_REVIEW"));
        listener.onVerificationCompleted(veredicto(party, prospecto, "VERIFIED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.party_verifications",
                Integer.class)).isEqualTo(1);
        assertThat(verifications.findByPartyId(party)).hasValueSatisfying(v -> {
            assertThat(v.getIdentityStatus()).isEqualTo("VERIFIED");
            assertThat(v.allowsDisposition()).isTrue();
        });
        assertThat(verifications.findByPartyId(prospecto)).hasValueSatisfying(v ->
                assertThat(v.getPartyId()).isEqualTo(party));
    }

    @Test
    @DisplayName("un evento viejo sin prospecto sigue proyectándose por partyId")
    void aLegacyEventWithOnlyPartyStillProjects() {
        UUID party = UUID.randomUUID();

        listener.onVerificationCompleted(veredicto(party, null, "VERIFIED"));

        assertThat(verifications.findByPartyId(party)).isPresent();
    }

    @Test
    @DisplayName("un evento sin ninguna de las dos llaves no deja fila de nadie")
    void anEventWithoutAnyKeyIsIgnored() {
        listener.onVerificationCompleted(veredicto(null, null, "VERIFIED"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_portfolio.party_verifications",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("la reentrega concurrente del mismo veredicto deja UNA fila por persona")
    void concurrentRedeliveryLeavesOneRow() throws Exception {
        UUID prospecto = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        int hilos = 8;

        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        for (int i = 0; i < hilos; i++) {
            tareas.add(pool.submit(() -> {
                salida.await();
                listener.onVerificationCompleted(veredicto(party, prospecto, "VERIFIED"));
                return null;
            }));
        }
        salida.countDown();
        int perdedores = 0;
        for (Future<?> t : tareas) {
            // El índice único es quien decide la carrera: los perdedores fallan y Kafka los
            // reentrega, y en la reentrega ya encuentran la fila y la actualizan.
            try { t.get(30, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException e) { perdedores++; }
        }
        pool.shutdown();
        for (int i = 0; i < perdedores; i++) {
            listener.onVerificationCompleted(veredicto(party, prospecto, "VERIFIED"));
        }

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM credit_portfolio.party_verifications WHERE prospect_id = ?",
                Integer.class, prospecto)).isEqualTo(1);
        assertThat(verifications.findByPartyId(party)).isPresent();
    }
}
