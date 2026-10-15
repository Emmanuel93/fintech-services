package com.fintech.compliance;

import com.fintech.compliance.application.service.PersonVerificationService;
import com.fintech.compliance.domain.ScreeningSubject;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La API de cumplimiento, de punta a punta.
 *
 * <p>Lo que estas pruebas cubren es lo que hace el control <b>operable</b>, no sólo correcto: sin
 * adjudicación, cualquier homónimo queda bloqueado para siempre; sin poder consultar las corridas,
 * nadie sabe si el re-cruce sigue vivo o se apagó hace meses.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComplianceAcceptanceTest extends AbstractIntegrationTest {

    @Override protected List<String> schemas() { return List.of("compliance"); }

    @Autowired MockMvc mockMvc;
    @Autowired PersonVerificationService verifications;

    /** Deja sembrada una lista vigente con un sancionado, para que el cruce tenga contra qué buscar. */
    private void seedSanction(String name) {
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_versions
                    (version_id, source_code, kind, file_hash, status, total_rows, accepted_rows,
                     rejected_rows, started_at, activated_at)
                VALUES (?, ?, 'SANCTIONS', ?, 'ACTIVE', 1, 1, 0, now(), now())
                """, versionId, "ACC-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID().toString());

        var n = com.fintech.compliance.domain.NormalizedName.of(name);
        jdbcTemplate.update("""
                INSERT INTO compliance.watchlist_entries
                    (entry_id, version_id, kind, raw_name, normalized_name, sorted_name,
                     curp, rfc, date_of_birth, country, program, source_line)
                VALUES (?, ?, 'SANCTIONS', ?, ?, ?, NULL, NULL, NULL, 'SY', 'SDN', 2)
                """, UUID.randomUUID(), versionId, name, n.value(), n.sorted());
    }

    private UUID registerPerson(String name) {
        UUID partyId = UUID.randomUUID();
        var v = verifications.evaluateNewPerson(
                new ScreeningSubject(partyId, name, LocalDate.of(1985, 3, 12), null, null, "MX"),
                UUID.randomUUID());
        return v.getVerificationId();
    }

    // ── El veredicto se puede consultar ──────────────────────────────────────────────────────

    @Test
    @DisplayName("una persona limpia queda con listas despejadas y la identidad en revisión")
    void acleanPersonIsClearedButStillInIdentityReview() throws Exception {
        seedSanction("IVAN PETROV");
        UUID partyId = UUID.randomUUID();
        verifications.evaluateNewPerson(new ScreeningSubject(
                partyId, "JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12), null, null, "MX"),
                UUID.randomUUID());

        mockMvc.perform(get("/api/v1/compliance/verifications/by-party/{id}", partyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("CLEARED"))
                // Sin proveedor de KYC toda identidad va a la cola. Bloquea, y es la regla.
                .andExpect(jsonPath("$.identityStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.allowsToProceed").value(false))
                .andExpect(jsonPath("$.blockReason").value("identidad IN_REVIEW"));
    }

    @Test
    @DisplayName("una persona sin verificación devuelve 404, no un veredicto inventado")
    void anUnknownPartyIsNotFound() throws Exception {
        // Importa que sea 404 y no un CLEARED por defecto: quien consulta tiene que distinguir
        // «no la hemos verificado» de «está limpia».
        mockMvc.perform(get("/api/v1/compliance/verifications/by-party/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    // ── Adjudicar: la salida que hace operable el control ────────────────────────────────────

    @Test
    @DisplayName("un homónimo bloqueado se despeja con autor y argumento, y pasa a CLEARED")
    void anamesakeCanBeCleared() throws Exception {
        seedSanction("JUAN PÉREZ GARCÍA");
        UUID verificationId = registerPerson("JUAN PEREZ GARCIA");

        mockMvc.perform(post("/api/v1/compliance/verifications/{id}/adjudicate", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CLEARED","decidedBy":"analista-1",
                                 "notes":"nació en 1985, el de la lista en 1962 y es sirio"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("CLEARED"))
                .andExpect(jsonPath("$.decidedBy").value("analista-1"))
                .andExpect(jsonPath("$.decisionNotes").value(org.hamcrest.Matchers.containsString("1962")));
    }

    @Test
    @DisplayName("adjudicar sin argumento se rechaza: es lo que sustenta la decisión")
    void adjudicatingWithoutAnArgumentIsRejected() throws Exception {
        seedSanction("JUAN PÉREZ GARCÍA");
        UUID verificationId = registerPerson("JUAN PEREZ GARCIA");

        mockMvc.perform(post("/api/v1/compliance/verifications/{id}/adjudicate", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CLEARED","decidedBy":"analista-1","notes":"  "}
                                """))
                .andExpect(status().is4xxClientError());
    }

    // ── El re-cruce, y su rastro ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el re-cruce detecta a quien se publicó después y lo devuelve a la cola")
    void therescreenCatchesSomeonePublishedLater() throws Exception {
        // La secuencia completa: la persona se da de alta limpia, después se publica una lista que
        // la incluye, y el re-cruce la encuentra. Sin esto su veredicto sería CLEARED para siempre.
        // Hace falta al menos una lista vigente ANTES de registrarla: sin ninguna, el veredicto es
        // PENDING y no CLEARED — porque decir «limpia» sin listas sería afirmar que se comprobó algo
        // que no se comprobó.
        seedSanction("IVAN PETROV");

        UUID partyId = UUID.randomUUID();
        verifications.evaluateNewPerson(new ScreeningSubject(
                partyId, "ROSA MARTINEZ LOPEZ", LocalDate.of(1985, 3, 12), null, null, "MX"),
                UUID.randomUUID());

        mockMvc.perform(get("/api/v1/compliance/verifications/by-party/{id}", partyId))
                .andExpect(jsonPath("$.screeningStatus").value("CLEARED"));

        seedSanction("ROSA MARTÍNEZ LÓPEZ");

        mockMvc.perform(post("/api/v1/compliance/rescreen"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.changedCount").value(1));

        mockMvc.perform(get("/api/v1/compliance/verifications/by-party/{id}", partyId))
                .andExpect(jsonPath("$.screeningStatus").value("POTENTIAL_MATCH"))
                .andExpect(jsonPath("$.allowsToProceed").value(false));
    }

    @Test
    @DisplayName("las corridas quedan consultables con su duración")
    void therunsAreQueryable() throws Exception {
        // «¿Cuándo fue el último re-cruce?» es la primera pregunta de una revisión.
        mockMvc.perform(post("/api/v1/compliance/rescreen")).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/compliance/rescreen-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].durationMs").isNumber());
    }

    @Test
    @DisplayName("un re-cruce sin nada que mirar termina bien, no falla")
    void anemptyRescreenCompletes() throws Exception {
        // Una cartera vacía es un caso legítimo —un ambiente recién levantado— y tiene que
        // distinguirse de un fallo: lo contrario llenaría de alertas falsas cualquier despliegue.
        mockMvc.perform(post("/api/v1/compliance/rescreen"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalScreened").value(0));
    }
    // ── La cola de identidad, por HTTP ───────────────────────────────────────────────────────

    @Test
    @DisplayName("un analista dictamina la identidad por HTTP y la persona queda habilitada")
    void ananalystCanCloseTheIdentityQueue() throws Exception {
        // Es la puerta que faltaba. El caso de uso existía —`recordIdentityVerdict`— y **no tenía un
        // solo llamador de producción**: una identidad en IN_REVIEW, que sin proveedor de KYC es
        // toda identidad, no se podía resolver desde ningún sitio. La cola tenía entrada y no salida.
        seedSanction("IVAN PETROV");
        UUID verificationId = registerPerson("JUAN PEREZ GARCIA");

        mockMvc.perform(post("/api/v1/compliance/verifications/{id}/identity", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"VERIFIED","decidedBy":"analista-kyc-2",
                                 "notes":"INE cotejada contra RENAPO y prueba de vida superada"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.decidedBy").value("analista-kyc-2"))
                // Y con las dos resueltas, la compuerta deja pasar.
                .andExpect(jsonPath("$.allowsToProceed").value(true));
    }

    @Test
    @DisplayName("un dictamen de identidad sin autor se rechaza con 400, no se guarda a medias")
    void anidentityVerdictWithoutAnAuthorIsRejected() throws Exception {
        UUID verificationId = registerPerson("JUAN PEREZ GARCIA");

        mockMvc.perform(post("/api/v1/compliance/verifications/{id}/identity", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"VERIFIED","decidedBy":"","notes":"cotejada"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("dejar la identidad en revisión no es un dictamen: 400")
    void leavingItInReviewIsNotAverdict() throws Exception {
        UUID verificationId = registerPerson("JUAN PEREZ GARCIA");

        mockMvc.perform(post("/api/v1/compliance/verifications/{id}/identity", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"IN_REVIEW","decidedBy":"analista-1","notes":"la dejo pendiente"}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ── Evaluar bajo demanda: la salida para la cartera que ya existía ───────────────────────

    @Test
    @DisplayName("se puede pedir la evaluación de una persona que ya existía, por HTTP")
    void anexistingPersonCanBeEvaluatedOnDemand() throws Exception {
        // El disparador normal es `origination.prospect-created`, y para la cartera histórica ese
        // evento ya pasó: sin esta puerta quedarían bloqueadas para siempre, no por una decisión
        // sino por una ausencia.
        seedSanction("IVAN PETROV");
        UUID prospectId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/compliance/verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prospectId":"%s","fullName":"JUAN PEREZ GARCIA",
                                 "dateOfBirth":"1985-03-12","country":"MX"}
                                """.formatted(prospectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("CLEARED"))
                .andExpect(jsonPath("$.identityStatus").value("IN_REVIEW"));

        // Y se puede consultar por prospecto, que es la llave que tienen los flujos de alta.
        mockMvc.perform(get("/api/v1/compliance/verifications/by-prospect/{id}", prospectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prospectId").value(prospectId.toString()));
    }

    @Test
    @DisplayName("pedirla dos veces NO reevalúa: no pisa una adjudicación ya firmada")
    void evaluatingTwiceDoesNotOverwriteAdjudications() throws Exception {
        // Es lo que hace seguro reintentar una remediación masiva. Sin idempotencia, correr el
        // script dos veces devolvería a la cola a gente que cumplimiento ya había despejado.
        seedSanction("JUAN PÉREZ GARCÍA");
        UUID prospectId = UUID.randomUUID();
        String cuerpo = """
                {"prospectId":"%s","fullName":"JUAN PEREZ GARCIA",
                 "dateOfBirth":"1985-03-12","country":"MX"}
                """.formatted(prospectId);

        String primera = mockMvc.perform(post("/api/v1/compliance/verifications")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("POTENTIAL_MATCH"))
                .andReturn().getResponse().getContentAsString();
        UUID verificationId = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(primera, "$.verificationId"));

        mockMvc.perform(post("/api/v1/compliance/verifications/{id}/adjudicate", verificationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"CLEARED","decidedBy":"analista-1",
                                 "notes":"nació en 1985, el de la lista en 1962"}
                                """))
                .andExpect(status().isOk());

        // Segunda petición: devuelve la MISMA verificación, con su adjudicación intacta.
        mockMvc.perform(post("/api/v1/compliance/verifications")
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(verificationId.toString()))
                .andExpect(jsonPath("$.screeningStatus").value("CLEARED"))
                .andExpect(jsonPath("$.decidedBy").value("analista-1"));
    }

    // ── El historial de consultas, por HTTP ──────────────────────────────────────────────────

    @Test
    @DisplayName("el historial de una persona dice cuándo se la consultó y qué salió ese día")
    void thecheckHistoryIsQueryable() throws Exception {
        // Es la pregunta de una revisión: «¿la consultaron? ¿cuándo? ¿qué decía la lista ese día?».
        // El veredicto vigente no la contesta porque se sobrescribe en cada re-cruce.
        seedSanction("IVAN PETROV");
        UUID prospectId = UUID.randomUUID();
        verifications.evaluateNewPerson(new ScreeningSubject(
                UUID.randomUUID(), "JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12),
                "PEGJ850312HDFXXX01", null, "MX"), prospectId);

        mockMvc.perform(get("/api/v1/compliance/verifications/by-prospect/{id}/checks", prospectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChecks").value(1))
                .andExpect(jsonPath("$.checks[0].trigger").value("ONBOARDING"))
                .andExpect(jsonPath("$.checks[0].result").value("CLEARED"))
                // Contra qué corrió: sin esto el resultado no es reproducible.
                .andExpect(jsonPath("$.checks[0].checkedAgainstLists").value(true))
                .andExpect(jsonPath("$.checks[0].listVersions").isNotEmpty())
                // Y con qué datos se consultó, que es lo que explica el resultado de entonces.
                .andExpect(jsonPath("$.checks[0].subjectFullName").value("JUAN PEREZ GARCIA"))
                .andExpect(jsonPath("$.checks[0].subjectCurp").value("PEGJ850312HDFXXX01"))
                .andExpect(jsonPath("$.checks[0].checkedAt").isNotEmpty());
    }

    @Test
    @DisplayName("una persona sin consultas devuelve un historial vacío, no un 404")
    void anunscreenedPersonHasAnemptyHistory() throws Exception {
        // Vacío es la respuesta correcta y es informativa: «no la hemos consultado nunca» es un
        // hecho del expediente, no un error de la petición.
        mockMvc.perform(get("/api/v1/compliance/verifications/by-prospect/{id}/checks",
                        UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChecks").value(0))
                .andExpect(jsonPath("$.checks").isEmpty());
    }

    @Test
    @DisplayName("la consulta de una coincidencia guarda a quién se parecía, para el expediente")
    void amatchIsVisibleInTheHistory() throws Exception {
        seedSanction("JUAN PÉREZ GARCÍA");
        UUID prospectId = UUID.randomUUID();
        verifications.evaluateNewPerson(new ScreeningSubject(
                UUID.randomUUID(), "JUAN PEREZ GARCIA", LocalDate.of(1985, 3, 12),
                null, null, "MX"), prospectId);

        mockMvc.perform(get("/api/v1/compliance/verifications/by-prospect/{id}/checks", prospectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[0].result").value("POTENTIAL_MATCH"))
                .andExpect(jsonPath("$.checks[0].candidateCount").value(
                        org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.checks[0].strongestListedName").value("JUAN PÉREZ GARCÍA"));
    }

}
