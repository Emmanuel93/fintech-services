package com.fintech.payments.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payments.application.PaymentsProperties;
import com.fintech.payments.application.service.BalanceSnapshotService;
import com.fintech.payments.application.service.PaymentService;
import com.fintech.payments.domain.*;
import com.fintech.payments.infrastructure.adapter.in.api.dto.ReversePaymentRequest;
import com.fintech.payments.infrastructure.adapter.in.api.dto.SubmitPaymentRequest;
import com.fintech.payments.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest for PaymentsController.
 *
 * Auth model: header-trust (X-User-Id injected by gateway), no JWT re-verification here.
 */
@WebMvcTest(PaymentsController.class)
@Import({SecurityConfig.class, PaymentsExceptionHandler.class, JwtAuthenticationFilter.class})
class PaymentsControllerTest {

    static final String BASE = "/api/v1/payments";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean PaymentService paymentService;
    @MockitoBean BalanceSnapshotService snapshotService;
    @MockitoBean PaymentsProperties properties;

    PaymentOrder buildOrder(UUID accountId) {
        return PaymentOrder.create(accountId, UUID.randomUUID(),
                new BigDecimal("1000"), PaymentMethod.SPEI, "REF-TEST", 1L);
    }

    @Test
    void submit_returns401_withoutToken() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SubmitPaymentRequest(
                                UUID.randomUUID(), UUID.randomUUID(),
                                new BigDecimal("1000"), "SPEI", "REF-001"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void submit_returns200_withValidToken() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(paymentService.submit(any(), any(), any(), any(), any())).thenReturn(buildOrder(accountId));

        mvc.perform(post(BASE)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new SubmitPaymentRequest(
                                accountId, UUID.randomUUID(), new BigDecimal("1000"), "SPEI", "REF-001"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void getBalance_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccountBalanceSnapshot snapshot = AccountBalanceSnapshot.init(accountId, UUID.randomUUID(), new BigDecimal("10000"));
        snapshot.update(new BigDecimal("8000"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("2000"), new BigDecimal("8000"), 2L, "ACTIVE");

        when(snapshotService.findByCreditAccountId(accountId)).thenReturn(Optional.of(snapshot));

        mvc.perform(get(BASE + "/accounts/" + accountId + "/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDebt").value(8000))
                .andExpect(jsonPath("$.balanceVersion").value(2));
    }

    @Test
    void getBalance_returns404_whenNoSnapshot() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(snapshotService.findByCreditAccountId(accountId)).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/accounts/" + accountId + "/balance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reverse_returns200_withValidToken() throws Exception {
        UUID orderId   = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        PaymentOrder order = buildOrder(accountId);
        order.confirm();
        order.reverse("SPEI devolution");

        when(paymentService.reverse(eq(orderId), any())).thenReturn(order);

        mvc.perform(post(BASE + "/" + orderId + "/reverse")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReversePaymentRequest("SPEI devolution"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }
}
