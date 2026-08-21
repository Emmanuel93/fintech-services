package com.fintech.origination.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintech.origination.application.PresentOfferCommand;
import com.fintech.origination.application.port.in.AcceptOfferUseCase;
import com.fintech.origination.application.port.in.PresentOfferUseCase;
import com.fintech.origination.application.port.in.RejectOfferUseCase;
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
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {OfferController.class, OriginationExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OriginationProperties.class})
@TestPropertySource(properties = {
        "fintech.origination.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo"
})
class OfferControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PresentOfferUseCase presentUseCase;
    @MockBean AcceptOfferUseCase acceptUseCase;
    @MockBean RejectOfferUseCase rejectUseCase;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final UUID appId = UUID.randomUUID();

    private CreditApplication appWithOffer() {
        CreditApplication app = CreditApplication.start(
                UUID.randomUUID(), ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.approve("BAJO", "AUTO_APPROVED");
        app.presentOffer(CreditOffer.create("PL-001", 1, "INSTALLMENT", "FRENCH",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"), new BigDecimal("3.0"),
                new BigDecimal("27.50"),
                Instant.now().plus(72, ChronoUnit.HOURS)));
        return app;
    }

    @Test
    @WithMockUser
    void presentOffer_validRequest_returns200OfferPresented() throws Exception {
        given(presentUseCase.present(any(PresentOfferCommand.class))).willReturn(appWithOffer());

        mockMvc.perform(post("/api/v1/origination/applications/{id}/offer", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "productCode", "PL-001",
                                "offeredAmount", 40000,
                                "offeredTerm", 12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFER_PRESENTED"))
                .andExpect(jsonPath("$.productCode").value("PL-001"))
                .andExpect(jsonPath("$.nominalRate").value(24.0));
    }

    @Test
    @WithMockUser
    void presentOffer_missingProductCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications/{id}/offer", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("offeredAmount", 40000))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void presentOffer_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/origination/applications/{id}/offer", appId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("productCode", "PL-001"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void acceptOffer_returns200OfferAccepted() throws Exception {
        CreditApplication app = appWithOffer();
        app.acceptOffer();
        given(acceptUseCase.accept(appId)).willReturn(app);

        mockMvc.perform(post("/api/v1/origination/applications/{id}/offer/accept", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFER_ACCEPTED"));
    }

    @Test
    @WithMockUser
    void rejectOffer_returns200OfferRejected() throws Exception {
        CreditApplication app = appWithOffer();
        app.rejectOffer();
        given(rejectUseCase.reject(appId)).willReturn(app);

        mockMvc.perform(post("/api/v1/origination/applications/{id}/offer/reject", appId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFER_REJECTED"));
    }
}
