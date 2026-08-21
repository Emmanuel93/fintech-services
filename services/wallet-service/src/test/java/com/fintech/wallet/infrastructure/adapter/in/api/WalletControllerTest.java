package com.fintech.wallet.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.application.port.in.CreatePaymentInstructionUseCase;
import com.fintech.wallet.application.port.in.GetWalletMovementsUseCase;
import com.fintech.wallet.application.port.in.GetWalletViewUseCase;
import com.fintech.wallet.application.port.in.RequestDispositionUseCase;
import com.fintech.wallet.application.port.in.WithdrawFromWalletUseCase;
import com.fintech.wallet.domain.*;
import com.fintech.wallet.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {WalletController.class, WalletExceptionHandler.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class WalletControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean GetWalletViewUseCase getWalletViewUseCase;
    // Lo usa el listado de movimientos, que estas pruebas no ejercitan; sin él el contexto
    // no arranca y fallan las catorce por una razón ajena a lo que comprueban.
    @MockitoBean GetWalletMovementsUseCase getWalletMovementsUseCase;
    @MockitoBean CreatePaymentInstructionUseCase createInstructionUseCase;
    @MockitoBean RequestDispositionUseCase requestDispositionUseCase;
    @MockitoBean WithdrawFromWalletUseCase withdrawFromWalletUseCase;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    private WalletView buildView() {
        return WalletView.createFromActivation(creditAccountId, obligorPartyId,
                "PERSONAL_LOAN", new BigDecimal("50000"), null);
    }

    private PaymentInstruction buildInstruction() {
        return PaymentInstruction.create(creditAccountId, obligorPartyId,
                PaymentMethod.SPEI, new BigDecimal("5000"), PaymentType.PARTIAL,
                null, java.time.Instant.now().plusSeconds(3600), null);
    }

    private WalletWithdrawal buildWithdrawal() {
        var w = WalletWithdrawal.create(creditAccountId, obligorPartyId,
                PaymentMethod.SPEI, new BigDecimal("1000"), "032180000118359719");
        w.markSent("WALLET-WD-STUB-TEST");
        return w;
    }

    // ── GET /wallet/{creditAccountId} ────────────────────────────────────────

    @Test
    void getWalletView_authenticated_returns200() throws Exception {
        given(getWalletViewUseCase.getByCreditAccountId(creditAccountId)).willReturn(buildView());

        mockMvc.perform(get("/api/v1/wallet/{id}", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditAccountId").value(creditAccountId.toString()))
                .andExpect(jsonPath("$.productType").value("PERSONAL_LOAN"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getWalletView_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/{id}", creditAccountId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getWalletView_notFound_returns404() throws Exception {
        given(getWalletViewUseCase.getByCreditAccountId(any()))
                .willThrow(new WalletViewNotFoundException(creditAccountId.toString()));

        mockMvc.perform(get("/api/v1/wallet/{id}", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString()))
                .andExpect(status().isNotFound());
    }

    // ── POST /wallet/{creditAccountId}/payment-instructions ─────────────────

    @Test
    void createInstruction_authenticated_returns201() throws Exception {
        given(createInstructionUseCase.create(any())).willReturn(buildInstruction());

        mockMvc.perform(post("/api/v1/wallet/{id}/payment-instructions", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "paymentMethod", "SPEI",
                                "amount", 5000,
                                "paymentType", "PARTIAL"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value("SPEI"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void createInstruction_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/{id}/payment-instructions", creditAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createInstruction_duplicatePending_returns409() throws Exception {
        given(createInstructionUseCase.create(any()))
                .willThrow(new DuplicatePendingInstructionException(
                        creditAccountId.toString(), "SPEI"));

        mockMvc.perform(post("/api/v1/wallet/{id}/payment-instructions", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "paymentMethod", "SPEI",
                                "amount", 5000,
                                "paymentType", "PARTIAL"))))
                .andExpect(status().isConflict());
    }

    @Test
    void createInstruction_insufficientCredit_returns422() throws Exception {
        given(createInstructionUseCase.create(any()))
                .willThrow(new InsufficientCreditException(
                        new BigDecimal("100000"), new BigDecimal("50000")));

        mockMvc.perform(post("/api/v1/wallet/{id}/payment-instructions", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "paymentMethod", "SPEI",
                                "amount", 100000,
                                "paymentType", "PARTIAL"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /wallet/{creditAccountId}/dispositions ─────────────────────────

    @Test
    void requestDisposition_authenticated_returns202() throws Exception {
        willDoNothing().given(requestDispositionUseCase).request(any());

        mockMvc.perform(post("/api/v1/wallet/{id}/dispositions", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "amount", 5000,
                                "dispositionType", "CASH_ADVANCE"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void requestDisposition_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/{id}/dispositions", creditAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /wallet/{creditAccountId}/withdrawals ───────────────────────────

    @Test
    void withdraw_authenticated_returns201() throws Exception {
        given(withdrawFromWalletUseCase.withdraw(any())).willReturn(buildWithdrawal());

        mockMvc.perform(post("/api/v1/wallet/{id}/withdrawals", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "method", "SPEI",
                                "amount", 1000,
                                "payeeAccount", "032180000118359719"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("SPEI"))
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void withdraw_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/wallet/{id}/withdrawals", creditAccountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withdraw_insufficientBalance_returns422() throws Exception {
        given(withdrawFromWalletUseCase.withdraw(any()))
                .willThrow(new InsufficientWalletBalanceException(
                        new BigDecimal("10000"), new BigDecimal("1000")));

        mockMvc.perform(post("/api/v1/wallet/{id}/withdrawals", creditAccountId)
                        .header("X-User-Id", obligorPartyId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "method", "SPEI",
                                "amount", 10000,
                                "payeeAccount", "032180000118359719"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── GET /wallet?partyId= / GET /wallet/summary?partyId= ─────────────────

    @Test
    void listByParty_returnsAllInstruments() throws Exception {
        given(getWalletViewUseCase.listByPartyId(obligorPartyId))
                .willReturn(java.util.List.of(buildView()));

        mockMvc.perform(get("/api/v1/wallet").param("partyId", obligorPartyId.toString())
                        .header("X-User-Id", obligorPartyId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creditAccountId").value(creditAccountId.toString()));
    }

    @Test
    void summary_returnsRollup() throws Exception {
        given(getWalletViewUseCase.listByPartyId(obligorPartyId))
                .willReturn(java.util.List.of(buildView()));

        mockMvc.perform(get("/api/v1/wallet/summary").param("partyId", obligorPartyId.toString())
                        .header("X-User-Id", obligorPartyId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instruments[0].creditAccountId").value(creditAccountId.toString()));
    }
}
