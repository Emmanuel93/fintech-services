package com.fintech.party.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.party.application.service.PartyRoleService;
import com.fintech.party.domain.PartyRole;
import com.fintech.party.domain.PartyRoleType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PartyRoleController.class)
@Import(PartyExceptionHandler.class)
class PartyRoleControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PartyRoleService roleService;

    private final ObjectMapper json = new ObjectMapper();
    private final UUID partyId = UUID.randomUUID();

    @Test
    void grant_returns201_andForwardsStaffHeader() throws Exception {
        given(roleService.grant(eq(partyId), eq(PartyRoleType.DISTRIBUTOR), eq("admin-7")))
                .willReturn(PartyRole.grant(partyId, PartyRoleType.DISTRIBUTOR, "admin-7"));

        mvc.perform(post("/api/v1/parties/{id}/roles", partyId)
                        .header("X-User-Id", "admin-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("roleType", "DISTRIBUTOR"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roleType").value("DISTRIBUTOR"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void grant_invalidRole_returns400() throws Exception {
        mvc.perform(post("/api/v1/parties/{id}/roles", partyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleType\":\"NOT_A_ROLE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsActiveRoles() throws Exception {
        given(roleService.listActive(partyId))
                .willReturn(List.of(PartyRole.grant(partyId, PartyRoleType.DISTRIBUTOR, "s")));

        mvc.perform(get("/api/v1/parties/{id}/roles", partyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roleType").value("DISTRIBUTOR"));
    }

    @Test
    void revoke_whenActive_returns204() throws Exception {
        given(roleService.revoke(partyId, PartyRoleType.DISTRIBUTOR)).willReturn(true);

        mvc.perform(delete("/api/v1/parties/{id}/roles/{role}", partyId, "DISTRIBUTOR"))
                .andExpect(status().isNoContent());
    }

    @Test
    void revoke_whenNoActive_returns404() throws Exception {
        given(roleService.revoke(partyId, PartyRoleType.GUARANTOR)).willReturn(false);

        mvc.perform(delete("/api/v1/parties/{id}/roles/{role}", partyId, "GUARANTOR"))
                .andExpect(status().isNotFound());
    }
}
