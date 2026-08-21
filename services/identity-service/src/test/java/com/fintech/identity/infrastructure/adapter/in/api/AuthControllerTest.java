package com.fintech.identity.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.application.LoginCommand;
import com.fintech.identity.application.LoginResult;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.TokenValidationResult;
import com.fintech.identity.application.port.in.*;
import com.fintech.identity.application.port.out.CredentialRepository;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.domain.CredentialType;
import com.fintech.identity.domain.InvalidCredentialsException;
import com.fintech.identity.domain.Channel;
import com.fintech.identity.infrastructure.adapter.in.api.dto.CredentialCreateRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.LoginRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.RefreshRequest;
import com.fintech.identity.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AuthController.class, IdentityExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = {
        "fintech.auth.jwt-secret=dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLWxvbmctZW5vdWdo",
        "fintech.auth.access-token-expiry-minutes=15",
        "fintech.auth.refresh-token-expiry-days=7",
        "fintech.auth.max-failed-attempts=5",
        "fintech.auth.lockout-duration-minutes=30"
})
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean LoginUseCase loginUseCase;
    @MockBean RefreshTokenUseCase refreshTokenUseCase;
    @MockBean LogoutUseCase logoutUseCase;
    @MockBean ValidateTokenUseCase validateTokenUseCase;
    @MockBean CreateCredentialUseCase createCredentialUseCase;
    // Lo usa /credentials/lookup, que estas pruebas no ejercitan; sin él el contexto no
    // arranca y fallan las diez por una razón ajena a lo que comprueban.
    @MockBean CredentialRepository credentialRepository;
    @MockBean TokenPort tokenPort;

    final UUID partyId = UUID.randomUUID();
    final String username = "user@test.com";

    // ── POST /login ───────────────────────────────────────────────────────

    @Test
    void login_validRequest_returns200WithTokens() throws Exception {
        TokenPair pair = new TokenPair("access.jwt", "refreshOpaque", 900L);
        given(loginUseCase.login(any(LoginCommand.class))).willReturn(new LoginResult.TokensIssued(pair));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "1234", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refreshOpaque"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void login_mfaRequired_returns202() throws Exception {
        given(loginUseCase.login(any(LoginCommand.class))).willReturn(new LoginResult.MfaRequired("mfa-pending-token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "1234", null))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mfaRequired").value(true))
                .andExpect(jsonPath("$.mfaToken").value("mfa-pending-token"));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        given(loginUseCase.login(any(LoginCommand.class))).willThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, "wrong", null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_missingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /refresh ─────────────────────────────────────────────────────

    @Test
    void refresh_validToken_returns200() throws Exception {
        TokenPair pair = new TokenPair("new.access.jwt", "newRefresh", 900L);
        given(refreshTokenUseCase.refresh("validRefresh")).willReturn(pair);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("validRefresh"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access.jwt"));
    }

    // ── POST /logout ──────────────────────────────────────────────────────

    @Test
    void logout_authenticated_returns204() throws Exception {
        willDoNothing().given(logoutUseCase).logout(any(UUID.class));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                "00000000-0000-0000-0000-000000000001", null,
                                List.of(new SimpleGrantedAuthority("ROLE_USER"))))))
                .andExpect(status().isNoContent());
    }

    // ── GET /validate ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000001")
    void validate_validBearer_returns200WithClaims() throws Exception {
        UUID pid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TokenValidationResult result = new TokenValidationResult(pid, List.of("CUSTOMER"), null, Channel.MOBILE);
        given(validateTokenUseCase.validate(any())).willReturn(result);

        mockMvc.perform(get("/api/v1/auth/validate")
                        .header("Authorization", "Bearer any.valid.jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(pid.toString()))
                .andExpect(jsonPath("$.roles[0]").value("CUSTOMER"));
    }

    @Test
    void validate_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/validate"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /credentials ─────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void createCredential_asAdmin_returns201() throws Exception {
        willDoNothing().given(createCredentialUseCase).createCredential(any(), any(), any(), any());

        mockMvc.perform(post("/api/v1/auth/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CredentialCreateRequest(partyId, username, "1234", CredentialType.NIP))))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void createCredential_asCustomer_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CredentialCreateRequest(partyId, username, "1234", CredentialType.NIP))))
                .andExpect(status().isForbidden());
    }
}
