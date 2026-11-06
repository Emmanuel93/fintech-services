package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import com.fintech.creditportfolio.application.service.BeneficiaryProfileService;
import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryProfile;
import com.fintech.creditportfolio.domain.beneficiary.ClientType;
import com.fintech.creditportfolio.domain.beneficiary.FlagReason;
import com.fintech.creditportfolio.domain.beneficiary.FlagSource;
import com.fintech.creditportfolio.domain.beneficiary.ReportReason;
import com.fintech.creditportfolio.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = BeneficiaryController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CreditPortfolioExceptionHandler.class})
class BeneficiaryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean BeneficiaryProfileService profiles;
    @MockitoBean ClientReferenceRepository references;

    final UUID client = UUID.randomUUID();
    final UUID her = UUID.randomUUID();

    BeneficiaryProfile aRedListedClient() {
        return new BeneficiaryProfile(client, ClientType.REVOLVENTE,
                List.of(new BeneficiaryProfile.FlagView(UUID.randomUUID(), FlagReason.DISTRIBUTOR_REPORT, FlagSource.DISTRIBUTOR,
                        Instant.parse("2026-11-01T12:00:00Z"), her, null, "NOT_LOCATABLE", "No contesta desde octubre")),
                3, 2, 1, new BigDecimal("2500.00"), List.of());
    }

    @Test
    void profile_answersTypeFlagsAndStanding() throws Exception {
        given(profiles.profile(client)).willReturn(aRedListedClient());

        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/{id}/profile", client).header("X-User-Id", "bo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REVOLVENTE"))
                .andExpect(jsonPath("$.redListed").value(true))
                .andExpect(jsonPath("$.flags[0].reason").value("DISTRIBUTOR_REPORT"))
                .andExpect(jsonPath("$.flags[0].comment").value("No contesta desde octubre"))
                .andExpect(jsonPath("$.loansPaid").value(2))
                .andExpect(jsonPath("$.outstanding").value(2500.00));
    }

    @Test
    void profile_withoutIdentity_is401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/{id}/profile", client))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void report_createsTheMarkWithTheCallerAsAuthor() throws Exception {
        BeneficiaryFlag flag = BeneficiaryFlag.report(client, FlagSource.DISTRIBUTOR, her, null, ReportReason.VOUCHER_UNPAID,
                "Le cubrí dos quincenas", her.toString(), Instant.parse("2026-11-05T12:00:00Z"));
        given(profiles.report(eq(client), eq(FlagSource.DISTRIBUTOR), eq(her), any(), eq(ReportReason.VOUCHER_UNPAID),
                eq("Le cubrí dos quincenas"), eq(her.toString()))).willReturn(flag);

        mockMvc.perform(post("/api/v1/portfolio/beneficiaries/{id}/flags", client)
                        .header("X-User-Id", her.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"VOUCHER_UNPAID\",\"comment\":\"Le cubrí dos quincenas\",\"source\":\"DISTRIBUTOR\",\"distributorPartyId\":\"" + her + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("DISTRIBUTOR_REPORT"))
                .andExpect(jsonPath("$.detail").value("VOUCHER_UNPAID"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void clear_ofAnotherClientsMark_is400() throws Exception {
        UUID someoneElse = UUID.randomUUID();
        BeneficiaryFlag flag = BeneficiaryFlag.report(someoneElse, FlagSource.DISTRIBUTOR, her, null, ReportReason.OTHER,
                "x", "y", Instant.parse("2026-11-05T12:00:00Z"));
        given(profiles.clear(eq(flag.getFlagId()), any(), any())).willReturn(flag);

        mockMvc.perform(post("/api/v1/portfolio/beneficiaries/{id}/flags/{flagId}/clear", client, flag.getFlagId())
                        .header("X-User-Id", "bo-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"aclarado\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void profiles_inBatch_areCappedAt200() throws Exception {
        given(profiles.profiles(any())).willReturn(List.of(aRedListedClient()));
        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/profiles").param("partyIds", client.toString()).header("X-User-Id", "bo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("REVOLVENTE"));

        String tooMany = IntStream.range(0, 201).mapToObj(i -> UUID.randomUUID().toString()).collect(Collectors.joining(","));
        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/profiles").param("partyIds", tooMany).header("X-User-Id", "bo-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void references_byLine_andByNumber() throws Exception {
        UUID line = UUID.randomUUID();
        ClientPaymentReference ref = ClientPaymentReference.of(1234566L, line, her, client, Instant.parse("2026-11-01T12:00:00Z"));
        given(references.findByLine(line)).willReturn(List.of(ref));
        given(references.findByReference(1234566L)).willReturn(Optional.of(ref));

        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/by-line/{id}/references", line).header("X-User-Id", "receipt-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].paymentReference").value(1234566))
                .andExpect(jsonPath("$[0].beneficiaryPartyId").value(client.toString()));
        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/references/{n}", 1234566L).header("X-User-Id", "payments-service"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditAccountId").value(line.toString()));
        mockMvc.perform(get("/api/v1/portfolio/beneficiaries/references/{n}", 7654321L).header("X-User-Id", "payments-service"))
                .andExpect(status().isNotFound());
    }
}
