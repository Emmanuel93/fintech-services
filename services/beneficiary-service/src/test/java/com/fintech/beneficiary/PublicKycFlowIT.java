package com.fintech.beneficiary;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.beneficiary.application.CreatePlacementCommand;
import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementEventPublisher;
import com.fintech.beneficiary.application.service.KycSubmissionService;
import com.fintech.beneficiary.application.service.PlacementInviteService;
import com.fintech.beneficiary.application.service.PublicKycService;
import com.fintech.beneficiary.domain.*;
import com.fintech.beneficiary.domain.event.BureauConsentGrantedEvent;
import com.fintech.beneficiary.domain.event.PlacementEvent;
import com.fintech.beneficiary.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.shared.banking.ClabeCheckDigit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * La web pública de KYC de punta a punta contra Postgres real.
 *
 * <p>Origination y party se sustituyen —son otros servicios—, pero todo lo de aquí es de verdad:
 * las cuatro tablas de la fase 3, sus {@code CHECK} y sus índices únicos, el candado de fila sobre
 * la liga y la transacción propia del prospecto recordado. Las pruebas de concurrencia viven aquí y
 * no en un test unitario porque lo que garantiza «un solo prospecto» es el {@code SELECT … FOR
 * UPDATE} de Postgres: con repositorios simulados se probaría el simulacro, no el candado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "fintech.beneficiary.public-kyc.dev-otp-code=123456",
        "fintech.beneficiary.public-kyc.link-base-url=http://localhost:4300/",
        "fintech.beneficiary.kyc-simulation-enabled=true"
})
class PublicKycFlowIT {

    private static final String BASE = "/api/v1/beneficiary/public";
    private static final String CLABE = ClabeCheckDigit.complete("07218000123456789");
    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);

    @Autowired MockMvc mockMvc;
    @Autowired PlacementLifecycleUseCase placements;
    @Autowired PlacementInviteService inviteService;
    @Autowired PublicKycService publicKyc;
    @Autowired KycSubmissionService submission;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @MockitoBean PlacementEventPublisher eventPublisher;
    @MockitoBean OriginationClient origination;
    @MockitoBean PartyClient party;

    @BeforeEach
    void stubDependencies() {
        given(origination.createIndividualProspect(any(), anyString())).willAnswer(inv -> {
            // Un origination lento ensancha la ventana de carrera: si el candado no serializara,
            // la segunda pestaña entraría mientras la primera espera aquí.
            Thread.sleep(150);
            return new OriginationClient.ProspectResponse(UUID.randomUUID(), "X", "X");
        });
        given(party.awaitByProspect(any(), anyInt(), anyLong())).willAnswer(inv ->
                Optional.of(new PartyClient.PartyResponse(UUID.randomUUID(), inv.getArgument(0),
                        "María Luisa", "Cortés", "Hernández", "ACTIVE", "PENDING", null, null)));
        given(party.findByProspect(any())).willReturn(Optional.empty());
    }

    /** Una colocación nueva —con distribuidor y celular propios— y su liga en claro. */
    private Fixture newPlacement() {
        String phone = String.format("55%08d", ThreadLocalRandom.current().nextInt(100_000_000));
        Placement p = placements.create(new CreatePlacementCommand(UUID.randomUUID(), UUID.randomUUID(),
                "DL-DIST-STD-V1", "María Luisa Cortés Hernández", phone, "Clienta",
                new BigDecimal("18000"), 12, new BigDecimal("868.06"),
                VerificationMode.SELF_SERVICE_LINK, Instant.now().plus(7, ChronoUnit.DAYS),
                LIMITS, new BigDecimal("205000"), null));
        return new Fixture(p.getPlacementId(), inviteService.mint(p).token());
    }

    record Fixture(UUID placementId, String token) {}

    private String verify(String token) throws Exception {
        mockMvc.perform(post(BASE + "/invites/" + token + "/otp")).andExpect(status().isOk());
        String body = mockMvc.perform(post(BASE + "/invites/" + token + "/otp/verify")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("sessionToken").asText();
    }

    private void captureEverything(String session) throws Exception {
        String ine = mockMvc.perform(multipart(BASE + "/session/ine")
                        .file(new MockMultipartFile("front", "f.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .file(new MockMultipartFile("back", "b.jpg", "image/jpeg", new byte[]{4, 5, 6}))
                        .header("X-Kyc-Session", session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode extracted = json.readTree(ine).get("extracted");

        mockMvc.perform(multipart(BASE + "/session/liveness")
                        .file(new MockMultipartFile("selfie", "s.jpg", "image/jpeg", new byte[]{7, 8}))
                        .header("X-Kyc-Session", session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review").value("MANUAL"));

        // Lo que la web haría: precargar con la lectura del OCR y completar lo que sólo ella sabe.
        String data = """
                {"firstName":"%s","lastName1":"%s","lastName2":"%s","curp":"%s","rfc":"%s",
                 "dateOfBirth":"%s","occupation":"Comerciante","monthlyIncome":12000,
                 "street":"%s","exteriorNumber":"%s","neighborhood":"%s","municipality":"%s",
                 "city":"%s","state":"%s","postalCode":"%s","clabe":"%s"}
                """.formatted(extracted.get("firstName").asText(), extracted.get("lastName1").asText(),
                extracted.get("lastName2").asText(), extracted.get("curp").asText(),
                extracted.get("rfc").asText(), extracted.get("dateOfBirth").asText(),
                extracted.get("street").asText(), extracted.get("exteriorNumber").asText(),
                extracted.get("neighborhood").asText(), extracted.get("municipality").asText(),
                extracted.get("city").asText(), extracted.get("state").asText(),
                extracted.get("postalCode").asText(), CLABE);
        mockMvc.perform(put(BASE + "/session/data").header("X-Kyc-Session", session)
                        .contentType(MediaType.APPLICATION_JSON).content(data))
                .andExpect(status().isOk());

        mockMvc.perform(post(BASE + "/session/consents").header("X-Kyc-Session", session)
                        .header("X-Forwarded-For", "189.203.4.5, 10.0.0.1")
                        .header("User-Agent", "Mozilla/5.0 (iPhone)")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"privacyNotice\":true,\"bureauAuthorization\":true,"
                               + "\"creditOwnership\":true,\"textVersion\":\"2026-10\"}"))
                .andExpect(status().isOk());
    }

    private int count(String table, UUID placementId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM beneficiary." + table + " WHERE placement_id = ?",
                Integer.class, placementId);
    }

    @Test
    @DisplayName("el flujo completo deja filas en las 4 tablas, la colocación en BUREAU_READY y la identidad PENDING")
    void fullPublicFlow() throws Exception {
        Fixture f = newPlacement();

        mockMvc.perform(get(BASE + "/invites/" + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lenderName").value("Tu distribuidora"))
                .andExpect(jsonPath("$.beneficiaryFirstName").value("María"))
                .andExpect(jsonPath("$.status").value("invited"));

        String session = verify(f.token());
        assertThat(placements.findById(f.placementId()).getStatus()).isEqualTo(PlacementStatus.KYC_IN_PROGRESS);

        mockMvc.perform(get(BASE + "/session").header("X-Kyc-Session", session))
                .andExpect(jsonPath("$.step").value("INE"));

        // Firmar antes de terminar: 409 con lo que falta.
        mockMvc.perform(post(BASE + "/session/submit").header("X-Kyc-Session", session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EXPEDIENTE_INCOMPLETO"));

        captureEverything(session);
        mockMvc.perform(get(BASE + "/session").header("X-Kyc-Session", session))
                .andExpect(jsonPath("$.step").value("SUBMIT"))
                .andExpect(jsonPath("$.consentsAccepted").value(true));

        mockMvc.perform(post(BASE + "/session/submit").header("X-Kyc-Session", session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("bureauReady"))
                .andExpect(jsonPath("$.firstName").value("María"));

        Placement done = placements.findById(f.placementId());
        assertThat(done.getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);
        assertThat(done.getIdentityDecision()).as("sin Incode: la revisa una persona").isEqualTo(IdentityDecision.PENDING);
        assertThat(done.getBeneficiaryClabe()).isEqualTo(CLABE);

        assertThat(count("placement_invites", f.placementId())).isEqualTo(1);
        assertThat(count("kyc_sessions", f.placementId())).isEqualTo(1);
        assertThat(count("kyc_documents", f.placementId())).isEqualTo(3);
        assertThat(count("consent_records", f.placementId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT ip_address FROM beneficiary.consent_records "
                        + "WHERE placement_id = ? AND consent_type = 'BUREAU_AUTHORIZATION'",
                String.class, f.placementId())).isEqualTo("189.203.4.5");
        assertThat(jdbc.queryForObject("SELECT redeemed_at IS NOT NULL FROM beneficiary.placement_invites "
                + "WHERE placement_id = ?", Boolean.class, f.placementId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT prospect_id IS NOT NULL AND closed_at IS NOT NULL "
                + "FROM beneficiary.kyc_sessions WHERE placement_id = ?", Boolean.class, f.placementId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM beneficiary.placement_invites WHERE token_hash = ?",
                Integer.class, f.token())).as("el token en claro no está en ninguna parte").isZero();

        ArgumentCaptor<PlacementEvent> events = ArgumentCaptor.forClass(PlacementEvent.class);
        then(eventPublisher).should(atLeastOnce()).publish(events.capture());
        assertThat(events.getAllValues()).filteredOn(e -> e instanceof BureauConsentGrantedEvent)
                .singleElement()
                .satisfies(e -> {
                    BureauConsentGrantedEvent c = (BureauConsentGrantedEvent) e;
                    assertThat(c.getConsentTextVersion()).isEqualTo("2026-10");
                    assertThat(c.getIpAddress()).isEqualTo("189.203.4.5");
                    assertThat(c.getOtpVerificationId()).isNotNull();
                });
        then(origination).should().createIndividualProspect(any(), eq("WEB"));
        then(origination).should(times(3)).uploadDocument(any(), anyString(), anyString(), anyString(), any());

        // La liga quedó quemada: la misma URL ya no abre nada.
        mockMvc.perform(get(BASE + "/invites/" + f.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("LIGA_NO_VALIDA"));
        mockMvc.perform(get(BASE + "/session").header("X-Kyc-Session", session))
                .andExpect(jsonPath("$.step").value("DONE"));

        // Y la mesa de KYC ve la evidencia.
        mockMvc.perform(get("/api/v1/backoffice/placements/" + f.placementId()).header("X-User-Id", "analista"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityEvidence.available").value(true))
                .andExpect(jsonPath("$.identityEvidence.reason").doesNotExist())
                .andExpect(jsonPath("$.identityEvidence.documents.length()").value(3))
                .andExpect(jsonPath("$.identityEvidence.captured.occupation").value("Comerciante"))
                .andExpect(jsonPath("$.identityEvidence.captured.clabe").doesNotExist())
                .andExpect(jsonPath("$.identityEvidence.captured.clabeMasked").value(
                        org.hamcrest.Matchers.endsWith(CLABE.substring(14))))
                .andExpect(jsonPath("$.identityEvidence.consents.length()").value(3));
        mockMvc.perform(get("/api/v1/backoffice/placements/" + f.placementId() + "/documents/SELFIE/file")
                        .header("X-User-Id", "analista"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(content().bytes(new byte[]{7, 8}));
    }

    @Test
    @DisplayName("sin documentos, la ficha dice por qué no hay evidencia")
    void evidenceUnavailableBeforeCapture() throws Exception {
        Fixture f = newPlacement();
        mockMvc.perform(get("/api/v1/backoffice/placements/" + f.placementId()).header("X-User-Id", "analista"))
                .andExpect(jsonPath("$.identityEvidence.available").value(false))
                .andExpect(jsonPath("$.identityEvidence.reason").isNotEmpty())
                .andExpect(jsonPath("$.identityEvidence.captured").doesNotExist());
    }

    @Test
    @DisplayName("rotar la liga desde soporte de pruebas revoca la anterior")
    void testSupportRotatesTheLink() throws Exception {
        Fixture f = newPlacement();
        String body = mockMvc.perform(post("/api/v1/internal/test-support/placements/" + f.placementId() + "/invite-link"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.link").value(org.hamcrest.Matchers.startsWith("http://localhost:4300/?t=")))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String fresh = json.readTree(body).get("token").asText();

        mockMvc.perform(get(BASE + "/invites/" + f.token())).andExpect(status().isNotFound());
        mockMvc.perform(get(BASE + "/invites/" + fresh)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM beneficiary.placement_invites "
                + "WHERE placement_id = ? AND revoked_at IS NULL", Integer.class, f.placementId())).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrencia: N pestañas verificando el mismo código abren UNA sola sesión")
    void concurrentVerifyOpensOneSession() throws Exception {
        Fixture f = newPlacement();
        publicKyc.sendOtp(f.token());

        List<Object> outcomes = race(6, () -> publicKyc.verifyOtp(f.token(), "123456", null, null));

        assertThat(outcomes).filteredOn(o -> o instanceof PublicKycService.GuestSession).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof PublicKycException.WrongCode).hasSize(5);
        assertThat(count("kyc_sessions", f.placementId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM beneficiary.placement_invites "
                        + "WHERE placement_id = ?", Integer.class, f.placementId()))
                .as("el doble toque no congela la liga").isZero();
    }

    @Test
    @DisplayName("concurrencia: N pestañas enviando la misma sesión crean UN solo prospecto")
    void concurrentSubmitCreatesOneProspect() throws Exception {
        Fixture f = newPlacement();
        String session = verify(f.token());
        captureEverything(session);

        List<Object> outcomes = race(6, () -> submission.submit(session, "123456"));

        assertThat(outcomes).allSatisfy(o -> assertThat(o)
                .isInstanceOfSatisfying(KycSubmissionService.SubmitResult.class,
                        r -> assertThat(r.status()).isEqualTo("bureauReady")));
        then(origination).should(times(1)).createIndividualProspect(any(), anyString());
        assertThat(placements.findById(f.placementId()).getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);
    }

    /** Arranca {@code n} hilos en el mismo instante y devuelve lo que cada uno obtuvo. */
    private static List<Object> race(int n, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> fu : futures) outcomes.add(fu.get(60, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
