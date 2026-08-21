package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.CreateOrgUnitRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrgUnitController.class)
@Import(SalesOrgExceptionHandler.class)
class OrgUnitControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean OrgUnitUseCase orgUnitUseCase;
    // El controlador consulta el IVA efectivo de una unidad directo al repositorio: la resolución
    // por herencia es una consulta sobre el path materializado, no una regla de negocio.
    @MockBean com.fintech.salesorg.application.port.out.OrgUnitRepository orgUnitRepository;

    private final ObjectMapper json = new ObjectMapper();

    private final UUID levelId = UUID.randomUUID();
    private final UUID unitId  = UUID.randomUUID();

    @Test
    void create_returns201_andForwardsStaffHeaderAsCreatedBy() throws Exception {
        given(orgUnitUseCase.create(any())).willReturn(
                OrgUnit.create(levelId, null, "MX", "México", "MX", null, "staff-9"));

        mockMvc.perform(post("/api/v1/sales-org/units")
                        .header("X-User-Id", "staff-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateOrgUnitRequest(levelId, null, "MX", "México", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MX"))
                .andExpect(jsonPath("$.path").value("MX"));

        ArgumentCaptor<CreateOrgUnitCommand> cmd = ArgumentCaptor.forClass(CreateOrgUnitCommand.class);
        verify(orgUnitUseCase).create(cmd.capture());
        assertThat(cmd.getValue().createdBy()).isEqualTo("staff-9");
    }

    @Test
    void get_notFound_returns404() throws Exception {
        given(orgUnitUseCase.get(unitId)).willThrow(new OrgUnitNotFoundException(unitId.toString()));

        mockMvc.perform(get("/api/v1/sales-org/units/{id}", unitId))
                .andExpect(status().isNotFound());
    }

    @Test
    void subtree_returnsList() throws Exception {
        UUID regionLevel = UUID.randomUUID();
        given(orgUnitUseCase.subtree(unitId)).willReturn(List.of(
                OrgUnit.create(regionLevel, UUID.randomUUID(), "NORTE", "Norte", "MX.NORTE", null, "s"),
                OrgUnit.create(regionLevel, UUID.randomUUID(), "MTY", "Monterrey", "MX.NORTE.MTY", null, "s")));

        mockMvc.perform(get("/api/v1/sales-org/units/{id}/subtree", unitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("MX.NORTE"))
                .andExpect(jsonPath("$[1].path").value("MX.NORTE.MTY"));
    }

    @Test
    void create_invalidHierarchy_returns400() throws Exception {
        given(orgUnitUseCase.create(any()))
                .willThrow(new InvalidHierarchyException("nivel incoherente"));

        mockMvc.perform(post("/api/v1/sales-org/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateOrgUnitRequest(levelId, null, "X", "X", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingLevelId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sales-org/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X\",\"name\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }
}
