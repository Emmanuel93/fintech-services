package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.salesorg.application.port.in.AssignToUnitCommand;
import com.fintech.salesorg.application.port.in.ManageAssignmentsUseCase;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.UnitAssignment;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.AssignRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UnitAssignmentController.class)
@Import(SalesOrgExceptionHandler.class)
class UnitAssignmentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ManageAssignmentsUseCase assignmentsUseCase;

    private final ObjectMapper json = new ObjectMapper();
    private final UUID unitId  = UUID.randomUUID();
    private final UUID staffId = UUID.randomUUID();

    @Test
    void assign_returns201_andForwardsStaffHeaderAsAssignedBy() throws Exception {
        given(assignmentsUseCase.assign(any())).willReturn(
                UnitAssignment.create(unitId, AssigneeType.STAFF, staffId, "MANAGER", "admin-7"));

        mockMvc.perform(post("/api/v1/sales-org/units/{unitId}/assignments", unitId)
                        .header("X-User-Id", "admin-7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new AssignRequest(AssigneeType.STAFF, staffId, "MANAGER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assigneeType").value("STAFF"))
                .andExpect(jsonPath("$.assignmentRole").value("MANAGER"))
                .andExpect(jsonPath("$.active").value(true));

        ArgumentCaptor<AssignToUnitCommand> cmd = ArgumentCaptor.forClass(AssignToUnitCommand.class);
        verify(assignmentsUseCase).assign(cmd.capture());
        assertThat(cmd.getValue().assignedBy()).isEqualTo("admin-7");
        assertThat(cmd.getValue().unitId()).isEqualTo(unitId);
    }

    @Test
    void scope_returnsSubtreeAssignments() throws Exception {
        given(assignmentsUseCase.scopeOfUnit(unitId)).willReturn(List.of(
                UnitAssignment.create(unitId, AssigneeType.STAFF, staffId, "MEMBER", "s")));

        mockMvc.perform(get("/api/v1/sales-org/units/{unitId}/scope", unitId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].assigneeId").value(staffId.toString()));
    }

    @Test
    void current_notFound_returns404() throws Exception {
        given(assignmentsUseCase.currentAssignment(AssigneeType.STAFF, staffId))
                .willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sales-org/assignments/{t}/{id}/current", "STAFF", staffId))
                .andExpect(status().isNotFound());
    }

    @Test
    void end_whenNoActive_returns404() throws Exception {
        given(assignmentsUseCase.endAssignment(AssigneeType.STAFF, staffId)).willReturn(0);

        mockMvc.perform(delete("/api/v1/sales-org/assignments/{t}/{id}", "STAFF", staffId))
                .andExpect(status().isNotFound());
    }

    @Test
    void end_whenActive_returns204() throws Exception {
        given(assignmentsUseCase.endAssignment(AssigneeType.STAFF, staffId)).willReturn(1);

        mockMvc.perform(delete("/api/v1/sales-org/assignments/{t}/{id}", "STAFF", staffId))
                .andExpect(status().isNoContent());
    }

    @Test
    void assign_missingAssigneeId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sales-org/units/{unitId}/assignments", unitId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeType\":\"STAFF\"}"))
                .andExpect(status().isBadRequest());
    }
}
