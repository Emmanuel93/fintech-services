package com.fintech.origination.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.GenerateContractCommand;
import com.fintech.origination.application.SignContractCommand;
import com.fintech.origination.application.port.in.GenerateContractUseCase;
import com.fintech.origination.application.port.in.SignContractUseCase;
import com.fintech.origination.domain.*;
import com.fintech.origination.infrastructure.config.OriginationProperties;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ContractController.class, OriginationExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OriginationProperties.class})
@TestPropertySource(properties = {
        "fintech.origination.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo"
})
class ContractControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GenerateContractUseCase generateUseCase;
    @MockBean SignContractUseCase signUseCase;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID appId = UUID.randomUUID();

    private CreditApplication appPendingSignature() {
        CreditApplication app = CreditApplication.start(
                UUID.randomUUID(), ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.approve("BAJO", "AUTO_APPROVED");
        app.presentOffer(CreditOffer.create("PL-001", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"), new BigDecimal("3.0"),
                new BigDecimal("27.50"),
                Instant.now().plus(72, ChronoUnit.HOURS)));
        app.acceptOffer();
        app.generateContract(Contract.generate("CTR-202606-TEST0001", "ELECTRONIC"));
        return app;
    }

    private CreditApplication appContractSigned() {
        CreditApplication app = appPendingSignature();
        app.signContract("032180000118359719", "DOC-REF-001");
        return app;
    }

    @Test
    @WithMockUser
    void generateContract_validRequest_returns200PendingSignature() throws Exception {
        given(generateUseCase.generate(any(GenerateContractCommand.class))).willReturn(appPendingSignature());

        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/generate", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signatureMethod", "ELECTRONIC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_SIGNATURE"))
                .andExpect(jsonPath("$.contractNumber").value("CTR-202606-TEST0001"))
                .andExpect(jsonPath("$.signatureMethod").value("ELECTRONIC"));
    }

    @Test
    @WithMockUser
    void generateContract_missingSignatureMethod_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/generate", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generateContract_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/generate", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("signatureMethod", "ELECTRONIC"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void signContract_validRequest_returns200ContractSigned() throws Exception {
        given(signUseCase.sign(any(SignContractCommand.class))).willReturn(appContractSigned());

        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/sign", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clabeAccount", "032180000118359719",
                                "signatureProof", "SIG-PROOF-ABC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONTRACT_SIGNED"))
                .andExpect(jsonPath("$.clabeAccount").value("032180000118359719"))
                .andExpect(jsonPath("$.contractSignedAt").isNotEmpty());
    }

    @Test
    @WithMockUser
    void signContract_invalidClabeFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/sign", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clabeAccount", "INVALID",
                                "signatureProof", "SIG-PROOF"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void signContract_missingSignatureProof_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications/{id}/contract/sign", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "clabeAccount", "032180000118359719"))))
                .andExpect(status().isBadRequest());
    }
}
