package com.fintech.configuration.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.configuration.application.ConfigServiceProperties;
import com.fintech.configuration.application.port.in.*;
import com.fintech.configuration.domain.ConfigParameter;
import com.fintech.configuration.domain.ConfigParameterNotFoundException;
import com.fintech.configuration.domain.InvalidConfigStateTransitionException;
import com.fintech.configuration.domain.ConfigParameterStatus;
import com.fintech.configuration.infrastructure.config.ConfigurationModuleConfig;
import com.fintech.configuration.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ConfigController.class, ConfigExceptionHandler.class})
@Import({SecurityConfig.class, ConfigurationModuleConfig.class})
@EnableConfigurationProperties(ConfigServiceProperties.class)
@TestPropertySource(properties = {
        "fintech.configuration.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo",
        "fintech.configuration.cache-ttl-seconds=60"
})
class ConfigControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean GetConfigParameterUseCase getConfigUseCase;
    @MockitoBean CreateConfigParameterUseCase createConfigUseCase;
    @MockitoBean ApproveConfigParameterUseCase approveConfigUseCase;
    @MockitoBean GetConfigHistoryUseCase getHistoryUseCase;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void getActive_existingKey_returns200() throws Exception {
        ConfigParameter param = ConfigParameter.create("vat_rate", "0.16",
                null, null, LocalDate.now(), UUID.randomUUID(), null, 1);
        param.approve(UUID.randomUUID());
        given(getConfigUseCase.getActive(eq("vat_rate"), any(), any()))
                .willReturn(Optional.of(param));

        mockMvc.perform(get("/api/v1/config/vat_rate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paramKey").value("vat_rate"))
                .andExpect(jsonPath("$.value").value("0.16"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void getActive_notFound_returns404() throws Exception {
        given(getConfigUseCase.getActive(eq("unknown"), any(), any()))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/config/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getActive_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/config/vat_rate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void create_validRequest_returns201() throws Exception {
        ConfigParameter param = ConfigParameter.create("grace_period_days", "3",
                "PERSONAL_LOAN", null, LocalDate.now(), UUID.randomUUID(), null, 1);
        given(createConfigUseCase.create(any())).willReturn(param);

        mockMvc.perform(post("/api/v1/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "paramKey", "grace_period_days",
                        "value", "3",
                        "productType", "PERSONAL_LOAN"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paramKey").value("grace_period_days"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void create_missingRequiredFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void approve_validId_returns200() throws Exception {
        UUID paramId = UUID.randomUUID();
        ConfigParameter param = ConfigParameter.create("vat_rate", "0.18",
                null, null, LocalDate.now(), UUID.randomUUID(), null, 2);
        param.approve(UUID.randomUUID());
        given(approveConfigUseCase.approve(eq(paramId), any())).willReturn(param);

        mockMvc.perform(put("/api/v1/config/{id}/approve", paramId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void approve_notFound_returns404() throws Exception {
        UUID unknownId = UUID.randomUUID();
        given(approveConfigUseCase.approve(eq(unknownId), any()))
                .willThrow(new ConfigParameterNotFoundException(unknownId));

        mockMvc.perform(put("/api/v1/config/{id}/approve", unknownId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void getHistory_returns200WithList() throws Exception {
        given(getHistoryUseCase.getHistory("vat_rate")).willReturn(List.of());

        mockMvc.perform(get("/api/v1/config/vat_rate/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
