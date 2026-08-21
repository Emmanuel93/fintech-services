package com.fintech.charges.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.charges.application.ChargesProperties;
import com.fintech.charges.application.port.out.AccrualScheduleRepository;
import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.application.service.BalanceSnapshotService;
import com.fintech.charges.application.service.ChargeReversalService;
import com.fintech.charges.domain.AccrualSchedule;
import com.fintech.charges.domain.ChargeRecord;
import com.fintech.charges.domain.ChargeType;
import com.fintech.charges.infrastructure.adapter.in.api.dto.ReverseChargeRequest;
import com.fintech.charges.infrastructure.adapter.in.api.dto.WaiveChargeRequest;
import com.fintech.charges.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Auth model: header-trust (X-User-Id injected by gateway).
 * GET endpoints are public; POST /reverse and POST /waive require X-User-Id.
 */
@WebMvcTest(ChargesController.class)
@Import({SecurityConfig.class, ChargesExceptionHandler.class, JwtAuthenticationFilter.class})
class ChargesControllerTest {

    static final String BASE = "/api/v1/charges";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean ChargeReversalService reversalService;
    @MockitoBean BalanceSnapshotService balanceSnapshotService;
    @MockitoBean ChargeRecordRepository chargeRecordRepo;
    @MockitoBean AccrualScheduleRepository scheduleRepo;
    @MockitoBean ChargesProperties properties;

    ChargeRecord buildRecord(UUID accountId) {
        return ChargeRecord.create(accountId, UUID.randomUUID(), ChargeType.ORDINARY_INTEREST,
                new BigDecimal("10000"), new BigDecimal("0.24"), 1,
                new BigDecimal("6.67"), BigDecimal.ZERO, LocalDate.now(), null);
    }

    @Test
    void getCharges_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(chargeRecordRepo.findAllByCreditAccountIdOrderByAccrualDateDesc(accountId))
                .thenReturn(List.of(buildRecord(accountId)));

        mvc.perform(get(BASE + "/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chargeType").value("ORDINARY_INTEREST"));
    }

    @Test
    void getSchedule_returns404_whenNotFound() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(scheduleRepo.findByCreditAccountId(accountId)).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/accounts/" + accountId + "/schedule"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getSchedule_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        AccrualSchedule s = AccrualSchedule.create(accountId, UUID.randomUUID(),
                "PERSONAL_LOAN", "AMORTIZING",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("10000"), new BigDecimal("10000"), 3);
        when(scheduleRepo.findByCreditAccountId(accountId)).thenReturn(Optional.of(s));

        mvc.perform(get(BASE + "/accounts/" + accountId + "/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void reverse_returns401_withoutToken() throws Exception {
        UUID chargeId = UUID.randomUUID();
        mvc.perform(post(BASE + "/" + chargeId + "/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReverseChargeRequest("error correction"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reverse_returns200_withValidToken() throws Exception {
        UUID chargeId  = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChargeRecord rec = buildRecord(accountId);
        when(reversalService.reverse(eq(chargeId), any())).thenReturn(rec);

        mvc.perform(post(BASE + "/" + chargeId + "/reverse")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ReverseChargeRequest("error correction"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
    }

    @Test
    void waive_returns200_withValidToken() throws Exception {
        UUID chargeId  = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ChargeRecord rec = buildRecord(accountId);
        when(reversalService.waive(eq(chargeId), any())).thenReturn(rec);

        mvc.perform(post(BASE + "/" + chargeId + "/waive")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "OPERATIONS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new WaiveChargeRequest("ops-admin"))))
                .andExpect(status().isOk());
    }
}
