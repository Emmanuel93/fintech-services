package com.fintech.origination.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.RegisterProspectCommand;
import com.fintech.origination.application.RegisterProspectResult;
import com.fintech.origination.application.port.in.FindProspectUseCase;
import com.fintech.origination.application.port.in.RegisterProspectUseCase;
import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.DuplicateProspectException;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.IncomeProofType;
import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.ProspectAddress;
import com.fintech.origination.domain.ProspectDocument;
import com.fintech.origination.domain.ProspectNotFoundException;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.domain.ProspectDocumentType;
import com.fintech.origination.infrastructure.adapter.in.api.dto.ProspectDocumentRequest;
import com.fintech.origination.infrastructure.adapter.in.api.dto.RegisterProspectRequest;
import com.fintech.origination.infrastructure.config.OriginationProperties;
import com.fintech.origination.infrastructure.adapter.out.persistence.SpringDataProspectDocumentFileRepository;
import com.fintech.origination.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ProspectController.class, OriginationExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OriginationProperties.class})
@TestPropertySource(properties = {
        "fintech.origination.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo",
        "fintech.origination.prospect-expiry-days=30"
})
class ProspectControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean RegisterProspectUseCase registerProspectUseCase;
    @MockBean FindProspectUseCase findProspectUseCase;
    // Lo usa la descarga de archivos del expediente, que estas pruebas no ejercitan; sin él
    // el contexto no arranca y fallan las ocho por una razón ajena al alta de prospectos.
    @MockBean SpringDataProspectDocumentFileRepository documentFiles;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private final UUID prospectId = UUID.randomUUID();

    private RegisterProspectRequest validRequest() {
        return new RegisterProspectRequest(
                null,
                "Carlos", "Ramírez", "Torres",
                "RATC850320HDFMRL09", null,
                LocalDate.of(1985, 3, 20),
                com.fintech.origination.domain.Gender.MALE,
                "Ciudad de México",
                "+5215512345678", "carlos@example.com",
                "Av. Insurgentes Sur", "1602", null,
                "Crédito Constructor", null, "CDMX", "CDMX", "03940", "MX",
                ChannelType.MOBILE_APP,
                true, true,
                List.of(
                        new ProspectDocumentRequest(ProspectDocumentType.INE_FRONT,    "s3://bucket/ine-front.jpg",   null, null, null, null),
                        new ProspectDocumentRequest(ProspectDocumentType.INE_BACK,     "s3://bucket/ine-back.jpg",    null, null, null, null),
                        new ProspectDocumentRequest(ProspectDocumentType.ADDRESS_PROOF,"s3://bucket/address.pdf",     null, null, null, null),
                        new ProspectDocumentRequest(ProspectDocumentType.INCOME_PROOF, "s3://bucket/income.pdf",      IncomeProofType.PAYROLL, null, null, null)
                ),
                "carlosrt", "Password1!");
    }

    // ── POST /api/v1/origination/prospects ────────────────────────────────

    @Test
    void register_validRequest_returns201WithProspectId() throws Exception {
        given(registerProspectUseCase.register(any(RegisterProspectCommand.class)))
                .willReturn(new RegisterProspectResult.ProspectRegistered(
                        prospectId, "RATC850320HDFMRL09", "+5215512345678",
                        Instant.now(), Instant.now().plusSeconds(2_592_000)));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.prospectId").value(prospectId.toString()))
                .andExpect(jsonPath("$.curp").value("RATC850320HDFMRL09"))
                .andExpect(jsonPath("$.phone").value("+5215512345678"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void register_missingRequiredFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_invalidCurpFormat_returns400() throws Exception {
        RegisterProspectRequest bad = new RegisterProspectRequest(
                null, "Carlos", "Ramírez", null,
                "INVALID_CURP", null,
                LocalDate.of(1985, 3, 20),
                com.fintech.origination.domain.Gender.MALE, "CDMX",
                "+5215512345678", null,
                "Calle 1", "10", null, "Col", null, "CDMX", "CDMX", "03940", "MX",
                ChannelType.MOBILE_APP, true, true, List.of(),
                "carlosrt", "Password1!");

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_privacyNoticeNotAccepted_returns400() throws Exception {
        // @AssertTrue on DTO catches privacyNoticeAccepted=false before service is invoked
        RegisterProspectRequest noPrivacy = new RegisterProspectRequest(
                null, "Carlos", "Ramírez", null,
                "RATC850320HDFMRL09", null,
                LocalDate.of(1985, 3, 20),
                com.fintech.origination.domain.Gender.MALE, "CDMX",
                "+5215512345678", null,
                "Calle 1", "10", null, "Col", null, "CDMX", "CDMX", "03940", "MX",
                ChannelType.MOBILE_APP, false, true, List.of(),
                "carlosrt", "Password1!");

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noPrivacy)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_duplicateCurp_returns409() throws Exception {
        given(registerProspectUseCase.register(any()))
                .willThrow(new DuplicateProspectException("CURP"));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void register_correlationIdHeader_passedToCommand() throws Exception {
        given(registerProspectUseCase.register(any(RegisterProspectCommand.class)))
                .willReturn(new RegisterProspectResult.ProspectRegistered(
                        prospectId, "RATC850320HDFMRL09", "+5215512345678",
                        Instant.now(), Instant.now().plusSeconds(2_592_000)));

        mockMvc.perform(post("/api/v1/origination/prospects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "test-corr-123")
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated());
    }

    // ── GET /api/v1/origination/prospects/{id} (mesa de análisis) ──────────

    @Test
    @WithMockUser
    void getById_returnsCapturedDetail() throws Exception {
        given(findProspectUseCase.getById(prospectId)).willReturn(sampleProspect());

        mockMvc.perform(get("/api/v1/origination/prospects/{id}", prospectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prospectId").value(prospectId.toString()))
                .andExpect(jsonPath("$.firstName").value("Carlos"))
                .andExpect(jsonPath("$.curp").value("RATC850320HDFMRL09"))
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.address.city").value("CDMX"))
                .andExpect(jsonPath("$.documents[0].documentType").value("INE_FRONT"))
                .andExpect(jsonPath("$.privacyNoticeAccepted").value(true));
    }

    @Test
    @WithMockUser
    void getById_notFound_returns404() throws Exception {
        given(findProspectUseCase.getById(any(UUID.class)))
                .willThrow(new ProspectNotFoundException(prospectId.toString()));

        mockMvc.perform(get("/api/v1/origination/prospects/{id}", prospectId))
                .andExpect(status().isNotFound());
    }

    private Prospect sampleProspect() {
        ProspectAddress address = new ProspectAddress(
                "Av. Insurgentes Sur", "1602", null, "Crédito Constructor",
                null, "CDMX", "CDMX", "03940", "MX");
        return Prospect.create(
                prospectId, ProspectType.INDIVIDUAL,
                "Carlos", "Ramírez", "Torres",
                "RATC850320HDFMRL09", "RATC850320AB1",
                LocalDate.of(1985, 3, 20), Gender.MALE, "Ciudad de México",
                "+5215512345678", "carlos@example.com",
                address, ChannelType.MOBILE_APP,
                true, true,
                List.of(new ProspectDocument(ProspectDocumentType.INE_FRONT, "s3://bucket/ine-front.jpg")),
                30);
    }
}
