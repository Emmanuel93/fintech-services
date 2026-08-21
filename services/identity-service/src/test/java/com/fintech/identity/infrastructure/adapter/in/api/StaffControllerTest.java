package com.fintech.identity.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.identity.application.CreateStaffCommand;
import com.fintech.identity.application.StaffLoginCommand;
import com.fintech.identity.application.StaffProfile;
import com.fintech.identity.application.StaffSession;
import com.fintech.identity.application.TokenPair;
import com.fintech.identity.application.port.in.FindStaffUseCase;
import com.fintech.identity.application.port.in.ManageStaffUseCase;
import com.fintech.identity.application.port.in.StaffLoginUseCase;
import com.fintech.identity.application.port.out.TokenPort;
import com.fintech.identity.domain.AccountLockedException;
import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.InvalidCredentialsException;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffStatus;
import com.fintech.identity.domain.StaffUser;
import com.fintech.identity.domain.StaffUserAlreadyExistsException;
import com.fintech.identity.domain.StaffUserNotFoundException;
import com.fintech.identity.infrastructure.adapter.in.api.dto.ChangeStaffRolesRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.CreateStaffRequest;
import com.fintech.identity.infrastructure.adapter.in.api.dto.StaffLoginRequest;
import com.fintech.identity.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {StaffAuthController.class, StaffController.class, IdentityExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class StaffControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean StaffLoginUseCase staffLoginUseCase;
    @MockBean ManageStaffUseCase manageStaffUseCase;
    @MockBean FindStaffUseCase findStaffUseCase;
    @MockBean TokenPort tokenPort;

    final UUID staffUserId = UUID.fromString("00000000-0000-4000-8000-000000000009");
    final String email = "ana.torres@kredius.mx";

    private StaffUser executive() {
        return StaffUser.create(email, "Ana Torres", null, EmployeeType.INTERNO, null,
                Set.of(StaffRole.EXECUTIVE), "$2a$10$hash");
    }

    private StaffSession session() {
        return new StaffSession(
                new TokenPair("staff.access.jwt", "staffRefresh", 900L),
                new StaffProfile(staffUserId, email, "Ana Torres",
                        EmployeeType.INTERNO, null, List.of("EXECUTIVE")));
    }

    // ── POST /auth/staff/login ────────────────────────────────────────────

    @Test
    void login_validRequest_returns200WithBackofficeChannel() throws Exception {
        given(staffLoginUseCase.login(any(StaffLoginCommand.class))).willReturn(session());

        mockMvc.perform(post("/api/v1/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StaffLoginRequest(email, "Backoffice#2026"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("staff.access.jwt"))
                .andExpect(jsonPath("$.channel").value("BACKOFFICE"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.roles[0]").value("EXECUTIVE"))
                // El hash de la contraseña nunca sale del servicio.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        willThrow(new InvalidCredentialsException())
                .given(staffLoginUseCase).login(any(StaffLoginCommand.class));

        mockMvc.perform(post("/api/v1/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StaffLoginRequest(email, "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_lockedAccount_returns423() throws Exception {
        willThrow(new AccountLockedException(Instant.now().plusSeconds(1800)))
                .given(staffLoginUseCase).login(any(StaffLoginCommand.class));

        mockMvc.perform(post("/api/v1/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StaffLoginRequest(email, "whatever"))))
                .andExpect(status().is(423));
    }

    @Test
    void login_malformedEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/staff/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new StaffLoginRequest("not-an-email", "Backoffice#2026"))))
                .andExpect(status().isBadRequest());
    }

    // ── /api/v1/staff — solo ADMIN ────────────────────────────────────────

    @Test
    void staffDirectory_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/staff"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "EXECUTIVE")
    void staffDirectory_withNonAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/staff"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staffDirectory_asAdmin_returnsList() throws Exception {
        given(findStaffUseCase.find(null, null)).willReturn(List.of(executive()));

        mockMvc.perform(get("/api/v1/staff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(email))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].passwordHash").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staffDirectory_filtersByStatusAndRole() throws Exception {
        given(findStaffUseCase.find(StaffStatus.ACTIVE, StaffRole.EXECUTIVE))
                .willReturn(List.of(executive()));

        mockMvc.perform(get("/api/v1/staff")
                        .param("status", "ACTIVE")
                        .param("role", "EXECUTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_returns201() throws Exception {
        given(manageStaffUseCase.create(any(CreateStaffCommand.class))).willReturn(executive());

        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                email, "Ana Torres", "TOAA900101HDFRNN09", EmployeeType.INTERNO, null,
                                Set.of(StaffRole.EXECUTIVE), "UnaClaveLarga2026"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                email, "Ana Torres", "TOAA900101HDFRNN09", EmployeeType.INTERNO, null,
                                Set.of(StaffRole.EXECUTIVE), "corta"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_duplicateEmail_returns409() throws Exception {
        willThrow(new StaffUserAlreadyExistsException(email))
                .given(manageStaffUseCase).create(any(CreateStaffCommand.class));

        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                email, "Ana Torres", "TOAA900101HDFRNN09", EmployeeType.INTERNO, null,
                                Set.of(StaffRole.EXECUTIVE), "UnaClaveLarga2026"))))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void changeRoles_emptySet_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/staff/{id}/roles", staffUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangeStaffRolesRequest(Set.of()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void getById_unknownStaff_returns404() throws Exception {
        willThrow(new StaffUserNotFoundException(staffUserId.toString()))
                .given(findStaffUseCase).getById(staffUserId);

        mockMvc.perform(get("/api/v1/staff/{id}", staffUserId))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disable_returnsDisabledStaff() throws Exception {
        StaffUser user = executive();
        user.disable();
        given(manageStaffUseCase.disable(staffUserId)).willReturn(user);

        mockMvc.perform(delete("/api/v1/staff/{id}", staffUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
    }

    // ── Identidad plena del colaborador y credencial de servicio ──────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_capturaLaCurp() throws Exception {
        given(manageStaffUseCase.create(any(CreateStaffCommand.class))).willReturn(executive());

        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                email, "Ana Torres", "TOAA900101HDFRNN09", EmployeeType.INTERNO, null,
                                Set.of(StaffRole.EXECUTIVE), "UnaClaveLarga2026"))))
                .andExpect(status().isCreated());

        var captor = ArgumentCaptor.forClass(CreateStaffCommand.class);
        verify(manageStaffUseCase).create(captor.capture());
        assertThat(captor.getValue().curp()).isEqualTo("TOAA900101HDFRNN09");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createStaff_curpMalFormada_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/staff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateStaffRequest(
                                email, "Ana Torres", "NO-ES-UNA-CURP", EmployeeType.INTERNO, null,
                                Set.of(StaffRole.EXECUTIVE), "UnaClaveLarga2026"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staffDirectory_exponeLaCurp_paraQueLaBitacoraLaCongele() throws Exception {
        given(findStaffUseCase.find(null, null)).willReturn(List.of(executiveConCurp()));

        mockMvc.perform(get("/api/v1/staff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].curp").value("TOAA900101HDFRNN09"));
    }

    /**
     * El caso que dejaba la bitácora anónima: el canal preguntando por un empleado.
     *
     * <p>Iba con el token del propio empleado y {@code /staff/**} es de ADMIN, así que todo el que
     * no fuera administrador recibía 403 pidiendo su propio nombre. Ahora el canal pregunta con su
     * credencial de servicio, cuyo rol sólo abre esta lectura puntual.
     */
    @Test
    @WithMockUser(roles = "SERVICE_DIRECTORY")
    void getById_conCredencialDeServicio_resuelveLaIdentidad() throws Exception {
        given(findStaffUseCase.getById(staffUserId)).willReturn(executiveConCurp());

        mockMvc.perform(get("/api/v1/staff/{id}", staffUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ana Torres"))
                .andExpect(jsonPath("$.curp").value("TOAA900101HDFRNN09"))
                .andExpect(jsonPath("$.email").value(email));
    }

    /** La credencial de servicio lee un empleado, no el directorio entero. */
    @Test
    @WithMockUser(roles = "SERVICE_DIRECTORY")
    void staffDirectory_conCredencialDeServicio_sigueSiendo403() throws Exception {
        mockMvc.perform(get("/api/v1/staff"))
                .andExpect(status().isForbidden());
    }

    /** Y no da de alta, ni cambia roles, ni toca contraseñas. */
    @Test
    @WithMockUser(roles = "SERVICE_DIRECTORY")
    void escrituras_conCredencialDeServicio_siguenSiendo403() throws Exception {
        mockMvc.perform(delete("/api/v1/staff/{id}", staffUserId))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/staff/{id}/roles", staffUserId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ChangeStaffRolesRequest(Set.of(StaffRole.ADMIN)))))
                .andExpect(status().isForbidden());
    }

    private StaffUser executiveConCurp() {
        return StaffUser.create(email, "Ana Torres", "TOAA900101HDFRNN09",
                EmployeeType.INTERNO, null, Set.of(StaffRole.EXECUTIVE), "$2a$10$hash");
    }
}
