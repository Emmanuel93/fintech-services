package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.application.port.out.PortfolioStat;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import com.fintech.creditportfolio.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CreditAccountController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CreditPortfolioExceptionHandler.class})
class CreditAccountControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean FindCreditAccountUseCase findUseCase;
    @MockitoBean CreditAccountRepository creditAccountRepository;
    @MockitoBean DispositionRepository dispositionRepository;
    @MockitoBean InstallmentRepository installmentRepository;

    // fromSnapshot generates its own creditAccountId internally; capture it from the built object
    private final CreditAccount account = buildAccount();
    private final UUID accountId = account.getCreditAccountId();

    private static CreditAccount buildAccount() {
        CreditAccount a = CreditAccount.fromSnapshot(
                UUID.randomUUID(), "CTR-202606-TEST0001", UUID.randomUUID(),
                "PL-001", 1, "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("50000"), null, 12,
                new BigDecimal("24.0"), new BigDecimal("36.0"),
                "FRENCH", BigDecimal.ZERO, "032180000118359719", "BAJO", null, null);
        a.activate(new BigDecimal("50000"));
        return a;
    }

    // ── GET /accounts/{id} ────────────────────────────────────────────────────────

    @Test
    void getById_authenticated_returns200WithAccountDetails() throws Exception {
        given(findUseCase.getById(accountId)).willReturn(account);

        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}", accountId)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.productType").value("PERSONAL_LOAN"))
                .andExpect(jsonPath("$.principalBalance").value(50000))
                .andExpect(jsonPath("$.totalDebt").value(50000));
    }

    @Test
    void getById_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}", accountId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        given(findUseCase.getById(any()))
                .willThrow(new CreditAccountNotFoundException(accountId.toString()));

        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}", accountId)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    // ── GET /accounts/search ──────────────────────────────────────────────────────

    @Test
    void search_authenticated_returnsPage() throws Exception {
        given(creditAccountRepository.search(any(), any(), any(), any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(account), PageRequest.of(0, 25), 1));
        given(installmentRepository.planSummaries(any())).willReturn(Map.of());

        mockMvc.perform(get("/api/v1/portfolio/accounts/search")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].creditAccountId").value(accountId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void search_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/accounts/search"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /accounts/batch ───────────────────────────────────────────────────────

    @Test
    void batch_authenticated_returnsAccounts() throws Exception {
        given(creditAccountRepository.findByCreditAccountIdIn(any())).willReturn(List.of(account));
        given(installmentRepository.planSummaries(any())).willReturn(Map.of());

        mockMvc.perform(get("/api/v1/portfolio/accounts/batch")
                        .param("ids", accountId.toString())
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void batch_tooManyIds_returns400() throws Exception {
        String[] ids = IntStream.range(0, 201)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toArray(String[]::new);

        mockMvc.perform(get("/api/v1/portfolio/accounts/batch")
                        .param("ids", ids)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
    }

    // ── GET /accounts/stats ───────────────────────────────────────────────────────

    @Test
    void stats_byStatus_returnsGroupedList() throws Exception {
        given(creditAccountRepository.stats("status"))
                .willReturn(List.of(new PortfolioStat("ACTIVE", 3, new BigDecimal("100.00"))));

        mockMvc.perform(get("/api/v1/portfolio/accounts/stats")
                        .param("groupBy", "status")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("ACTIVE"))
                .andExpect(jsonPath("$[0].count").value(3));
    }

    @Test
    void stats_invalidGroupBy_returns400() throws Exception {
        // El controlador valida groupBy en el borde; el repositorio no se llega a invocar.
        mockMvc.perform(get("/api/v1/portfolio/accounts/stats")
                        .param("groupBy", "nope")
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
    }

    // ── GET /accounts/{id}/dispositions ──────────────────────────────────────────

    @Test
    void getDispositions_authenticated_returns200List() throws Exception {
        given(findUseCase.getById(accountId)).willReturn(account);
        given(dispositionRepository.findByCreditAccountId(accountId)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}/dispositions", accountId)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getDispositions_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}/dispositions", accountId))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /accounts/{id}/amortization-schedule ─────────────────────────────────

    @Test
    void getSchedule_authenticated_returns200List() throws Exception {
        given(findUseCase.getById(accountId)).willReturn(account);
        given(installmentRepository.findByScheduleId(accountId)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}/amortization-schedule", accountId)
                        .header("X-User-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void getSchedule_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/accounts/{id}/amortization-schedule", accountId))
                .andExpect(status().isUnauthorized());
    }
}
