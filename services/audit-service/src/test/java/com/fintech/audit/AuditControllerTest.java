package com.fintech.audit;

import com.fintech.audit.application.service.AuditService;
import com.fintech.audit.application.service.DocumentArchiveService;
import com.fintech.audit.application.service.UIFReportService;
import com.fintech.audit.domain.AuditEntry;
import com.fintech.audit.domain.AuditEntryNotFoundException;
import com.fintech.audit.infrastructure.adapter.in.api.AuditExceptionHandler;
import com.fintech.audit.infrastructure.adapter.in.api.JwtAuthenticationFilter;
import com.fintech.audit.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuditExceptionHandler.class})
class AuditControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean AuditService auditService;
    @MockitoBean DocumentArchiveService documentArchiveService;
    @MockitoBean UIFReportService uifReportService;

    private static final String AUDITOR_HEADER_USER  = "X-User-Id";
    private static final String AUDITOR_HEADER_ROLES = "X-Roles";

    @Test
    void listEntries_requiresAuth() throws Exception {
        mvc.perform(get("/api/v1/audit/entries"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listEntries_forbiddenForCustomerRole() throws Exception {
        mvc.perform(get("/api/v1/audit/entries")
                        .header(AUDITOR_HEADER_USER, UUID.randomUUID().toString())
                        .header(AUDITOR_HEADER_ROLES, "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listEntries_okForAuditorRole() throws Exception {
        AuditEntry entry = AuditEntry.create("TEST", "svc", "agg", "party", null, "{}");
        when(auditService.findByDateRange(any(Instant.class), any(Instant.class), anyInt())).thenReturn(List.of(entry));

        mvc.perform(get("/api/v1/audit/entries")
                        .header(AUDITOR_HEADER_USER, UUID.randomUUID().toString())
                        .header(AUDITOR_HEADER_ROLES, "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listEntries_filterByPartyId() throws Exception {
        String partyId = UUID.randomUUID().toString();
        when(auditService.findByPartyId(eq(partyId), any(), any(), anyInt())).thenReturn(List.of());

        mvc.perform(get("/api/v1/audit/entries?partyId=" + partyId)
                        .header(AUDITOR_HEADER_USER, UUID.randomUUID().toString())
                        .header(AUDITOR_HEADER_ROLES, "REGULATOR"))
                .andExpect(status().isOk());
    }

    @Test
    void getEntry_returnsDetail() throws Exception {
        UUID id = UUID.randomUUID();
        AuditEntry entry = AuditEntry.create("EV", "svc", "agg", null, null, "{\"data\":1}");
        when(auditService.findById(id)).thenReturn(entry);

        mvc.perform(get("/api/v1/audit/entries/" + id)
                        .header(AUDITOR_HEADER_USER, UUID.randomUUID().toString())
                        .header(AUDITOR_HEADER_ROLES, "AUDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("{\"data\":1}"));
    }

    @Test
    void getEntry_returns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(auditService.findById(id)).thenThrow(new AuditEntryNotFoundException(id.toString()));

        mvc.perform(get("/api/v1/audit/entries/" + id)
                        .header(AUDITOR_HEADER_USER, UUID.randomUUID().toString())
                        .header(AUDITOR_HEADER_ROLES, "AUDITOR"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getExpedition_returnsAllSections() throws Exception {
        UUID partyId = UUID.randomUUID();
        when(auditService.findByPartyId(eq(partyId.toString()), any(), any(), anyInt())).thenReturn(List.of());
        when(documentArchiveService.findByPartyId(partyId)).thenReturn(List.of());
        when(uifReportService.findByPartyId(partyId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/audit/parties/" + partyId + "/expedition")
                        .header(AUDITOR_HEADER_USER, UUID.randomUUID().toString())
                        .header(AUDITOR_HEADER_ROLES, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.auditEntries").isArray())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.uifReports").isArray());
    }
}
