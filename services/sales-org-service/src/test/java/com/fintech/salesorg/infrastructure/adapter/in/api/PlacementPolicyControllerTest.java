package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.application.EffectivePlacementPolicy;
import com.fintech.salesorg.application.port.in.PlacementPolicyUseCase;
import com.fintech.salesorg.domain.ClientLimits;
import com.fintech.salesorg.domain.InvalidPlacementPolicyException;
import com.fintech.salesorg.domain.PlacementPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlacementPolicyController.class)
@Import(SalesOrgExceptionHandler.class)
class PlacementPolicyControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean PlacementPolicyUseCase policies;

    static final PlacementPolicy BRANCH = PlacementPolicy.of(
            new ClientLimits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16),
            new ClientLimits(new BigDecimal("10000"), new BigDecimal("15000"), 3, 24));

    static EffectivePlacementPolicy effective(String code, PlacementPolicy declared, PlacementPolicy effective, String from) {
        return new EffectivePlacementPolicy(UUID.randomUUID(), code, "MX.R.Z." + code, "BRANCH", null, declared, effective, from);
    }

    @Test
    void put_declaresThePolicyWithTheStaffHeader_andAnswersDeclaredAndEffective() throws Exception {
        given(policies.declare(eq("S_TIJ"), any(), eq("staff-9"))).willReturn(effective("S_TIJ", BRANCH, BRANCH, "S_TIJ"));

        mockMvc.perform(put("/api/v1/sales-org/units/by-code/{code}/placement-policy", "S_TIJ")
                        .header("X-User-Id", "staff-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nuevo": {"maxLoanAmount": 5000, "maxOutstanding": 5000, "maxActiveLoans": 1, "maxTerm": 16},
                                 "revolvente": {"maxLoanAmount": 10000, "maxOutstanding": 15000, "maxActiveLoans": 3, "maxTerm": 24}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S_TIJ"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.productType").value("DISTRIBUTOR_LINE"))
                .andExpect(jsonPath("$.inheritedFrom").value("S_TIJ"))
                .andExpect(jsonPath("$.declared.nuevo.maxLoanAmount").value(5000))
                .andExpect(jsonPath("$.effective.revolvente.maxActiveLoans").value(3));

        ArgumentCaptor<PlacementPolicy> sent = ArgumentCaptor.forClass(PlacementPolicy.class);
        verify(policies).declare(eq("S_TIJ"), sent.capture(), eq("staff-9"));
        assertThat(sent.getValue()).isEqualTo(BRANCH);
    }

    @Test
    void get_answersWhatAppliesAndWhereItCameFrom_nullDeclaredWhenOnlyInherited() throws Exception {
        given(policies.effective("D_ANA")).willReturn(effective("D_ANA", PlacementPolicy.EMPTY, BRANCH, "S_TIJ"));

        mockMvc.perform(get("/api/v1/sales-org/units/by-code/{code}/placement-policy", "D_ANA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.inheritedFrom").value("S_TIJ"))
                .andExpect(jsonPath("$.declared").doesNotExist())
                .andExpect(jsonPath("$.effective.nuevo.maxTerm").value(16));
    }

    @Test
    void get_withNothingConfiguredSaysSo() throws Exception {
        given(policies.effective("S_GDL")).willReturn(effective("S_GDL", PlacementPolicy.EMPTY, PlacementPolicy.EMPTY, null));

        mockMvc.perform(get("/api/v1/sales-org/units/by-code/{code}/placement-policy/effective", "S_GDL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.effective").doesNotExist());
    }

    @Test
    void put_thatDoesNotFitTheProduct_is400WithTheReason() throws Exception {
        given(policies.declare(eq("S_TIJ"), any(), any()))
                .willThrow(new InvalidPlacementPolicyException("La política no cabe en el producto DL-001: NUEVO: maxLoanAmount 12000 rebasa el máximo del producto 10000"));

        mockMvc.perform(put("/api/v1/sales-org/units/by-code/{code}/placement-policy", "S_TIJ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nuevo\": {\"maxLoanAmount\": 12000}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("rebasa el máximo")))
                .andExpect(jsonPath("$.type").value("https://fintech.com/errors/INVALID_PLACEMENT_POLICY"));
    }

    @Test
    void delete_clearsAndAnswersWhatIsInheritedNow() throws Exception {
        given(policies.clear("D_ANA", "staff-2")).willReturn(effective("D_ANA", PlacementPolicy.EMPTY, BRANCH, "S_TIJ"));

        mockMvc.perform(delete("/api/v1/sales-org/units/by-code/{code}/placement-policy", "D_ANA").header("X-User-Id", "staff-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inheritedFrom").value("S_TIJ"));
    }
}
