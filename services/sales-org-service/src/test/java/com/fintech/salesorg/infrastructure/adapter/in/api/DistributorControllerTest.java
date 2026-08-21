package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DistributorController.class)
@Import(SalesOrgExceptionHandler.class)
class DistributorControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean OrgUnitUseCase orgUnitUseCase;

    @Test
    void byCode_found_returnsPartyId() throws Exception {
        UUID distId = UUID.randomUUID();
        given(orgUnitUseCase.resolveDistributorByCode("DIST0001")).willReturn(Optional.of(distId));

        mockMvc.perform(get("/api/v1/sales-org/distributors/by-code/{code}", "DIST0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(distId.toString()));
    }

    @Test
    void byCode_notFound_returns404() throws Exception {
        given(orgUnitUseCase.resolveDistributorByCode("NOPE")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sales-org/distributors/by-code/{code}", "NOPE"))
                .andExpect(status().isNotFound());
    }
}
