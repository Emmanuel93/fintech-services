package com.fintech.identity.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.application.*;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.domain.*;
import com.fintech.identity.infrastructure.adapter.in.api.dto.*;
import com.fintech.identity.infrastructure.config.SecurityConfig;
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
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {ClientAuthController.class, IdentityExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = {
        "fintech.auth.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo",
        "fintech.auth.access-token-expiry-minutes=15",
        "fintech.auth.refresh-token-expiry-days=7",
        "fintech.auth.max-failed-attempts=5",
        "fintech.auth.lockout-duration-minutes=30"
})
class ClientAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ClientAuthUseCase clientAuthUseCase;
    @MockBean RegisterClientUseCase registerClientUseCase;
    @MockBean ManageClientWhitelistUseCase manageClientWhitelistUseCase;
    @MockBean TokenPort tokenPort;

    final String clientId = "scoring-service";
    final UUID entryId = UUID.randomUUID();

    // ── POST /token (público) ─────────────────────────────────────────────

    @Test
    void token_validCredentials_returns200WithTokenPair() throws Exception {
        TokenPair pair = new TokenPair("access.jwt", "refresh.opaque", 900L);
        given(clientAuthUseCase.authenticateClient(eq(clientId), eq("secret123"), anyString()))
                .willReturn(pair);

        mockMvc.perform(post("/api/v1/auth/clients/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ClientTokenRequest(clientId, "secret123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh.opaque"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void token_invalidSecret_returns401() throws Exception {
        given(clientAuthUseCase.authenticateClient(anyString(), anyString(), anyString()))
                .willThrow(new ClientSecretInvalidException());

        mockMvc.perform(post("/api/v1/auth/clients/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ClientTokenRequest(clientId, "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void token_ipNotAllowed_returns403() throws Exception {
        given(clientAuthUseCase.authenticateClient(anyString(), anyString(), anyString()))
                .willThrow(new IpNotAllowedException("10.0.0.1"));

        mockMvc.perform(post("/api/v1/auth/clients/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ClientTokenRequest(clientId, "secret"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void token_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/clients/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void token_emptyClientId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/clients/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ClientTokenRequest("", "secret"))))
                .andExpect(status().isBadRequest());
    }

    // ── POST / (ADMIN) ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void register_asAdmin_returns201WithClientSecret() throws Exception {
        ClientRegistrationResult result = new ClientRegistrationResult(
                UUID.randomUUID(), clientId, "aabbcc112233445566778899aabbcc11"
                + "aabbcc112233445566778899aabbcc11",
                "Scoring Service", ClientStatus.ACTIVE,
                List.of("SYSTEM_SCORING"), null, Instant.now());
        given(registerClientUseCase.registerClient(eq(clientId), eq("Scoring Service"),
                eq(List.of("SYSTEM_SCORING")), isNull()))
                .willReturn(result);

        mockMvc.perform(post("/api/v1/auth/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterClientRequest(clientId, "Scoring Service",
                                        List.of("SYSTEM_SCORING"), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.clientId").value(clientId))
                .andExpect(jsonPath("$.clientSecret").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void register_asCustomer_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterClientRequest(clientId, "Scoring",
                                        List.of("SYSTEM"), null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void register_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterClientRequest(clientId, "Scoring",
                                        List.of("SYSTEM"), null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void register_missingRoles_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"scoring\",\"clientName\":\"Scoring\",\"roles\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /{clientId} (ADMIN) ───────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void getClient_asAdmin_returns200WithInfo() throws Exception {
        ClientInfo info = new ClientInfo(UUID.randomUUID(), clientId, "Scoring Service",
                ClientStatus.ACTIVE, List.of("SYSTEM_SCORING"), null, Instant.now());
        given(manageClientWhitelistUseCase.getClient(clientId)).willReturn(info);

        mockMvc.perform(get("/api/v1/auth/clients/{clientId}", clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(clientId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("SYSTEM_SCORING"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getClient_notFound_returns404() throws Exception {
        given(manageClientWhitelistUseCase.getClient(clientId))
                .willThrow(new ClientNotFoundException(clientId));

        mockMvc.perform(get("/api/v1/auth/clients/{clientId}", clientId))
                .andExpect(status().isNotFound());
    }

    @Test
    void getClient_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/clients/{clientId}", clientId))
                .andExpect(status().isUnauthorized());
    }

    // ── DELETE /{clientId} (ADMIN) ────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void disableClient_asAdmin_returns204() throws Exception {
        willDoNothing().given(manageClientWhitelistUseCase).disableClient(clientId);

        mockMvc.perform(delete("/api/v1/auth/clients/{clientId}", clientId))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void disableClient_asCustomer_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/clients/{clientId}", clientId))
                .andExpect(status().isForbidden());
    }

    // ── POST /{clientId}/whitelist (ADMIN) ─────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void addEntry_asAdmin_returns201WithEntry() throws Exception {
        WhitelistEntryResult result = new WhitelistEntryResult(
                UUID.randomUUID(), "10.0.0.1/32", "prod", Instant.now());
        given(manageClientWhitelistUseCase.addWhitelistEntry(
                eq(clientId), eq("10.0.0.1/32"), eq("prod")))
                .willReturn(result);

        mockMvc.perform(post("/api/v1/auth/clients/{clientId}/whitelist", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WhitelistEntryRequest("10.0.0.1/32", "prod"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cidr").value("10.0.0.1/32"))
                .andExpect(jsonPath("$.label").value("prod"))
                .andExpect(jsonPath("$.id").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addEntry_missingCidr_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/clients/{clientId}/whitelist", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new WhitelistEntryRequest("", "label"))))
                .andExpect(status().isBadRequest());
    }

    // ── GET /{clientId}/whitelist (ADMIN) ──────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void listEntries_asAdmin_returns200WithList() throws Exception {
        List<WhitelistEntryResult> entries = List.of(
                new WhitelistEntryResult(UUID.randomUUID(), "10.0.0.1/32", "prod", Instant.now()),
                new WhitelistEntryResult(UUID.randomUUID(), "192.168.0.0/16", "dev", Instant.now()));
        given(manageClientWhitelistUseCase.listWhitelistEntries(clientId)).willReturn(entries);

        mockMvc.perform(get("/api/v1/auth/clients/{clientId}/whitelist", clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cidr").value("10.0.0.1/32"))
                .andExpect(jsonPath("$[1].cidr").value("192.168.0.0/16"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listEntries_emptyList_returns200WithEmptyArray() throws Exception {
        given(manageClientWhitelistUseCase.listWhitelistEntries(clientId)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/auth/clients/{clientId}/whitelist", clientId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── DELETE /{clientId}/whitelist/{entryId} (ADMIN) ─────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void removeEntry_asAdmin_returns204() throws Exception {
        willDoNothing().given(manageClientWhitelistUseCase).removeWhitelistEntry(entryId);

        mockMvc.perform(delete("/api/v1/auth/clients/{clientId}/whitelist/{entryId}",
                        clientId, entryId))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeEntry_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/clients/{clientId}/whitelist/{entryId}",
                        clientId, entryId))
                .andExpect(status().isUnauthorized());
    }
}
