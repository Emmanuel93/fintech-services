package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.salesorg.application.port.in.OrgLevelUseCase;
import com.fintech.salesorg.domain.DuplicateCodeException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.infrastructure.adapter.in.api.dto.CreateOrgLevelRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrgLevelController.class)
@Import(SalesOrgExceptionHandler.class)
class OrgLevelControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean OrgLevelUseCase orgLevelUseCase;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void list_returnsLadder() throws Exception {
        given(orgLevelUseCase.list()).willReturn(List.of(
                OrgLevel.create("NATIONAL", "Nacional", 0),
                OrgLevel.create("REGION", "Región", 1)));

        mockMvc.perform(get("/api/v1/sales-org/levels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("NATIONAL"))
                .andExpect(jsonPath("$[0].depth").value(0))
                .andExpect(jsonPath("$[1].depth").value(1));
    }

    @Test
    void create_withoutDepth_appends() throws Exception {
        given(orgLevelUseCase.append("BRANCH", "Sucursal"))
                .willReturn(OrgLevel.create("BRANCH", "Sucursal", 3));

        mockMvc.perform(post("/api/v1/sales-org/levels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateOrgLevelRequest("BRANCH", "Sucursal", null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depth").value(3));

        verify(orgLevelUseCase).append("BRANCH", "Sucursal");
    }

    @Test
    void create_withDepth_insertsAtThatPosition() throws Exception {
        given(orgLevelUseCase.insertAt(eq(1), eq("SUBREGION"), any()))
                .willReturn(OrgLevel.create("SUBREGION", "Subregión", 1));

        mockMvc.perform(post("/api/v1/sales-org/levels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateOrgLevelRequest("SUBREGION", "Subregión", 1))))
                .andExpect(status().isCreated());

        verify(orgLevelUseCase).insertAt(1, "SUBREGION", "Subregión");
    }

    @Test
    void create_duplicateCode_returns409() throws Exception {
        given(orgLevelUseCase.append(any(), any())).willThrow(new DuplicateCodeException("REGION"));

        mockMvc.perform(post("/api/v1/sales-org/levels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateOrgLevelRequest("REGION", "Región", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void create_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/sales-org/levels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }
}
