package com.fintech.party.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.party.application.service.PartyService;
import com.fintech.party.domain.*;
import com.fintech.party.infrastructure.adapter.in.api.dto.BlacklistPartyRequest;
import com.fintech.party.infrastructure.adapter.in.api.dto.UpdateKycStatusRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PartyController.class)
@Import(PartyExceptionHandler.class)
class PartyControllerTest {

    static final String BASE = "/api/v1/parties";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PartyService partyService;

    // ── GET /{partyId} ────────────────────────────────────────────────────────

    @Test
    void getById_returns200_whenFound() throws Exception {
        UUID partyId = UUID.randomUUID();
        Party party = buildParty(partyId);
        when(partyService.findById(partyId)).thenReturn(Optional.of(party));

        mvc.perform(get(BASE + "/" + partyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partyId").value(partyId.toString()))
                .andExpect(jsonPath("$.status").value("PROSPECT"));
    }

    @Test
    void getById_returns404_whenNotFound() throws Exception {
        UUID partyId = UUID.randomUUID();
        when(partyService.findById(partyId)).thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/" + partyId))
                .andExpect(status().isNotFound());
    }

    // ── GET / (búsqueda paginada) ─────────────────────────────────────────────

    @Test
    void search_returns200_withPagedResults() throws Exception {
        UUID partyId = UUID.randomUUID();
        Page<Party> page = new PageImpl<>(List.of(buildParty(partyId)), PageRequest.of(0, 25), 1);
        when(partyService.search(any(), any(), any(), any(), any())).thenReturn(page);

        mvc.perform(get(BASE).param("q", "garcía"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].partyId").value(partyId.toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void search_bindsTypeAndStatusFilters() throws Exception {
        when(partyService.search(any(), eq(PartyType.BUSINESS), eq(PartyStatus.ACTIVE), any(), any()))
                .thenReturn(Page.empty());

        mvc.perform(get(BASE).param("type", "BUSINESS").param("status", "ACTIVE"))
                .andExpect(status().isOk());

        // q y executiveId ausentes llegan como null; type/status se convierten al enum de dominio.
        verify(partyService).search(isNull(), eq(PartyType.BUSINESS), eq(PartyStatus.ACTIVE), isNull(), any());
    }

    @Test
    void search_returns400_whenTypeInvalid() throws Exception {
        mvc.perform(get(BASE).param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }

    // ── GET /batch (hidratación por lote) ─────────────────────────────────────

    @Test
    void batch_returns200_withParties() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(partyService.findByIds(any())).thenReturn(List.of(buildParty(a), buildParty(b)));

        mvc.perform(get(BASE + "/batch").param("ids", a.toString(), b.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void batch_returns400_whenTooManyIds() throws Exception {
        String[] ids = IntStream.range(0, 201)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toArray(String[]::new);

        mvc.perform(get(BASE + "/batch").param("ids", ids))
                .andExpect(status().isBadRequest());

        verify(partyService, never()).findByIds(any());
    }

    @Test
    void batch_byProspectId_usesProspectLookup() throws Exception {
        UUID a = UUID.randomUUID();
        when(partyService.findByProspectIds(any())).thenReturn(List.of(buildParty(a)));

        mvc.perform(get(BASE + "/batch").param("ids", a.toString()).param("by", "prospectId"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        verify(partyService).findByProspectIds(any());
        verify(partyService, never()).findByIds(any());
    }

    @Test
    void batch_returns400_whenByInvalid() throws Exception {
        mvc.perform(get(BASE + "/batch").param("ids", UUID.randomUUID().toString()).param("by", "nope"))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /{partyId}/kyc-status ─────────────────────────────────────────────

    @Test
    void updateKycStatus_returns200_withVerifiedStatus() throws Exception {
        UUID partyId = UUID.randomUUID();
        KycVerification verification = KycVerification.create(partyId, "INE");
        verification.verify("RENAPO-API", "doc-ref-001", LocalDate.of(2027, 1, 1));
        when(partyService.addKycVerification(eq(partyId), any(), any(), any(), any(), any(), any()))
                .thenReturn(verification);

        mvc.perform(put(BASE + "/" + partyId + "/kyc-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateKycStatusRequest(
                                "INE", "VERIFIED", "RENAPO-API",
                                "doc-ref-001", LocalDate.of(2027, 1, 1), null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.documentType").value("INE"))
                .andExpect(jsonPath("$.verifiedBy").value("RENAPO-API"));
    }

    @Test
    void updateKycStatus_returns404_whenPartyNotFound() throws Exception {
        UUID partyId = UUID.randomUUID();
        when(partyService.addKycVerification(eq(partyId), any(), any(), any(), any(), any(), any()))
                .thenThrow(new PartyNotFoundException(partyId));

        mvc.perform(put(BASE + "/" + partyId + "/kyc-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateKycStatusRequest(
                                "INE", "VERIFIED", null, null, null, null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateKycStatus_returns400_whenDocumentTypeInvalid() throws Exception {
        UUID partyId = UUID.randomUUID();

        mvc.perform(put(BASE + "/" + partyId + "/kyc-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateKycStatusRequest(
                                "INVALID_DOC", "VERIFIED", null, null, null, null))))
                .andExpect(status().isBadRequest());
    }

    // ── POST /{partyId}/blacklist ─────────────────────────────────────────────

    @Test
    void blacklist_returns200_andStatusBlacklisted() throws Exception {
        UUID partyId = UUID.randomUUID();
        Party party = buildParty(partyId);
        party.blacklist("AML match OFAC");
        when(partyService.blacklistParty(eq(partyId), any(), any())).thenReturn(party);

        mvc.perform(post(BASE + "/" + partyId + "/blacklist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BlacklistPartyRequest(
                                "AML match OFAC", "OFAC"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLACKLISTED"));
    }

    @Test
    void blacklist_returns404_whenPartyNotFound() throws Exception {
        UUID partyId = UUID.randomUUID();
        when(partyService.blacklistParty(eq(partyId), any(), any()))
                .thenThrow(new PartyNotFoundException(partyId));

        mvc.perform(post(BASE + "/" + partyId + "/blacklist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BlacklistPartyRequest("reason", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void blacklist_returns409_whenAlreadyBlacklisted() throws Exception {
        UUID partyId = UUID.randomUUID();
        when(partyService.blacklistParty(eq(partyId), any(), any()))
                .thenThrow(new IllegalStateException("Party " + partyId + " is already BLACKLISTED"));

        mvc.perform(post(BASE + "/" + partyId + "/blacklist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BlacklistPartyRequest("AML match", null))))
                .andExpect(status().isConflict());
    }

    @Test
    void blacklist_returns400_whenReasonBlank() throws Exception {
        UUID partyId = UUID.randomUUID();

        mvc.perform(post(BASE + "/" + partyId + "/blacklist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new BlacklistPartyRequest("", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listKycVerifications_returns200_withEmptyList() throws Exception {
        UUID partyId = UUID.randomUUID();
        when(partyService.findKycVerifications(partyId)).thenReturn(List.of());

        mvc.perform(get(BASE + "/" + partyId + "/kyc-verifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Party buildParty(UUID partyId) {
        return Party.create(partyId, UUID.randomUUID(),
                PartyType.INDIVIDUAL, "Juan", "García", "López",
                "GARJ900101HDFXXX01", "GARJ900101XXX",
                LocalDate.of(1990, 1, 1));
    }
}
