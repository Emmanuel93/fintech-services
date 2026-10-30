package com.fintech.compliance;

import com.fintech.compliance.application.service.PersonVerificationService;
import com.fintech.compliance.domain.IdentityStatus;
import com.fintech.compliance.domain.PersonVerification;
import com.fintech.compliance.domain.ScreeningSubject;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La cola de la mesa de cumplimiento, contra Postgres real.
 *
 * <p>Lo que importa aquí es que la mesa <b>encuentre</b> a quien tiene que dictaminar: sin
 * proveedor de KYC toda alta cae en {@code IN_REVIEW}, y un filtro que se equivoca de estado o un
 * orden que esconde al más viejo convierte el dictamen manual en algo que existe y nadie hace.
 */
@SpringBootTest
@AutoConfigureMockMvc
class VerificationQueueIT extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired MockMvc mockMvc;
    @Autowired PersonVerificationService verifications;

    @BeforeEach
    void limpia() {
        jdbcTemplate.update("DELETE FROM compliance.screening_checks");
        jdbcTemplate.update("DELETE FROM compliance.person_verifications");
    }

    private PersonVerification alta(String nombre, String curp) {
        return verifications.evaluateNewPerson(
                new ScreeningSubject(null, nombre, LocalDate.of(1990, 6, 12), curp, null, "MX"),
                UUID.randomUUID());
    }

    @Test
    @DisplayName("la cola por defecto de la mesa —identidad en revisión— trae a quien espera dictamen")
    void theIdentityQueueListsWhoIsWaiting() throws Exception {
        alta("ROSA ELENA DIAZ FUENTES", "DIFR900612MDFZNS08");
        var dictaminada = alta("JUAN PEREZ GARCIA", "PEGJ900612HDFRRN05");
        verifications.adjudicateIdentity(dictaminada.getVerificationId(), IdentityStatus.VERIFIED,
                "analista-1", "INE y selfie coinciden");

        mockMvc.perform(get("/api/v1/compliance/verifications").param("identityStatus", "IN_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("ROSA ELENA DIAZ FUENTES"))
                .andExpect(jsonPath("$.content[0].curp").value("DIFR900612MDFZNS08"))
                .andExpect(jsonPath("$.content[0].identityStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.content[0].createdAt").exists());
    }

    @Test
    @DisplayName("los filtros se repiten y, vacíos, significan todos")
    void filtersRepeatAndEmptyMeansAll() throws Exception {
        alta("ROSA ELENA DIAZ FUENTES", "DIFR900612MDFZNS08");
        var v = alta("JUAN PEREZ GARCIA", "PEGJ900612HDFRRN05");
        verifications.adjudicateIdentity(v.getVerificationId(), IdentityStatus.REJECTED,
                "analista-1", "la selfie no corresponde a la INE");

        mockMvc.perform(get("/api/v1/compliance/verifications"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/compliance/verifications")
                        .param("identityStatus", "IN_REVIEW").param("identityStatus", "REJECTED"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/compliance/verifications").param("identityStatus", "REJECTED"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].decidedBy").value("analista-1"));
    }

    @Test
    @DisplayName("se busca por nombre o CURP, parcial y sin distinguir mayúsculas")
    void searchByNameOrCurp() throws Exception {
        alta("ROSA ELENA DIAZ FUENTES", "DIFR900612MDFZNS08");
        alta("JUAN PEREZ GARCIA", "PEGJ900612HDFRRN05");

        mockMvc.perform(get("/api/v1/compliance/verifications").param("q", "diaz fu"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].fullName").value("ROSA ELENA DIAZ FUENTES"));
        mockMvc.perform(get("/api/v1/compliance/verifications").param("q", "pegj9006"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("el más viejo va primero: es quien lleva más tiempo bloqueado")
    void theOldestComesFirst() throws Exception {
        alta("PRIMERA PERSONA ALTA", null);
        alta("SEGUNDA PERSONA ALTA", null);

        mockMvc.perform(get("/api/v1/compliance/verifications").param("identityStatus", "IN_REVIEW"))
                .andExpect(jsonPath("$.content[0].fullName").value("PRIMERA PERSONA ALTA"))
                .andExpect(jsonPath("$.content[1].fullName").value("SEGUNDA PERSONA ALTA"));
    }

    @Test
    @DisplayName("la ficha se pide por id, y un id desconocido es 404")
    void detailByIdAndUnknownIsNotFound() throws Exception {
        var v = alta("ROSA ELENA DIAZ FUENTES", "DIFR900612MDFZNS08");

        mockMvc.perform(get("/api/v1/compliance/verifications/{id}", v.getVerificationId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(v.getVerificationId().toString()))
                .andExpect(jsonPath("$.dateOfBirth").value("1990-06-12"));
        mockMvc.perform(get("/api/v1/compliance/verifications/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("varios analistas dictaminando a la vez vacían la cola sin perder ni duplicar a nadie")
    void concurrentVerdictsEmptyTheQueue() throws Exception {
        int personas = 12;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < personas; i++) {
            ids.add(alta("PERSONA CONCURRENTE " + (char) ('A' + i), null).getVerificationId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        for (int i = 0; i < personas; i++) {
            UUID id = ids.get(i);
            String analista = "analista-" + (i % 3);
            tareas.add(pool.submit(() -> {
                salida.await();
                // Cada uno además lee la cola mientras otros escriben: es lo que pasa en una mesa.
                mockMvc.perform(get("/api/v1/compliance/verifications").param("identityStatus", "IN_REVIEW"))
                        .andExpect(status().isOk());
                return verifications.adjudicateIdentity(id, IdentityStatus.VERIFIED, analista,
                        "INE y selfie coinciden");
            }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(90, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM compliance.person_verifications WHERE identity_status = 'VERIFIED'",
                Integer.class)).isEqualTo(personas);
        mockMvc.perform(get("/api/v1/compliance/verifications").param("identityStatus", "IN_REVIEW"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
