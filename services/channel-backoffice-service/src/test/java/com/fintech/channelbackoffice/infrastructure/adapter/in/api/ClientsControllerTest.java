package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient.ApplicationResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClientsControllerTest {

    private final PartyClient party = mock(PartyClient.class);
    private final CreditPortfolioClient portfolio = mock(CreditPortfolioClient.class);
    private final OriginationClient origination = mock(OriginationClient.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final ClientsController controller =
            new ClientsController(party, portfolio, origination, identity);

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsClientShapeWithFullName() {
        when(party.search(any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new PartyClient.PageResponse<>(List.of(party(UUID.randomUUID())), 0, 25, 1, 1));

        var resp = controller.search("gar", null, null, null, 0, 25, "createdAt,desc");
        var content = (List<Map<String, Object>>) resp.getBody().get("content");

        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("fullName", "Juan García López").containsKey("riskLevel");
        assertThat(resp.getBody()).containsEntry("totalElements", 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detail_returnsClientWithAccountsAndApplications() {
        UUID partyId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        when(party.getByPartyId(partyId)).thenReturn(party(partyId, prospectId));
        when(portfolio.listByParty(prospectId)).thenReturn(List.of(account(prospectId)));
        when(origination.findByProspect(prospectId)).thenReturn(List.of(application()));

        var resp = controller.detail(partyId);
        Map<String, Object> body = resp.getBody();

        assertThat(body).containsEntry("fullName", "Juan García López");
        assertThat((List<?>) body.get("accounts")).hasSize(1);
        assertThat((List<?>) body.get("applications")).hasSize(1);
    }

    @Test
    void assignExecutive_resolvesNameFromDirectory_andDelegates() {
        UUID partyId = UUID.randomUUID();
        UUID executiveId = UUID.randomUUID();
        when(identity.listExecutives(any()))
                .thenReturn(List.of(new IdentityClient.ExecutiveResponse(executiveId, "Ana Torres")));
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("Authorization", "Bearer test-token");

        var resp = controller.assignExecutive(
                partyId, new ClientsController.AssignExecutiveRequest(executiveId.toString()), http);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        // El nombre se resuelve del directorio y se desnormaliza en party.
        verify(party).assignExecutive(partyId, executiveId, "Ana Torres");
    }

    @Test
    void detail_notFound_returns404() {
        UUID partyId = UUID.randomUUID();
        when(party.getByPartyId(partyId)).thenReturn(null);

        var resp = controller.detail(partyId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── builders ────────────────────────────────────────────────────────────

    private static PartyResponse party(UUID partyId) {
        return party(partyId, UUID.randomUUID());
    }

    private static PartyResponse party(UUID partyId, UUID prospectId) {
        return new PartyResponse(
                partyId, prospectId, null, "INDIVIDUAL", "ACTIVE",
                "Juan", "García", "López", "GARJ900101HDFXXX01", "GARJ900101XXX",
                null, "BAJO", 700, null, null, Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static CreditAccountResponse account(UUID obligor) {
        return new CreditAccountResponse(
                UUID.randomUUID(), "CTR-1", obligor, "PL-001", "PERSONAL_LOAN", "INSTALLMENT", "ACTIVE",
                null, null, new BigDecimal("1000"), null, null, null, null,
                null, 0, "BAJO", null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static ApplicationResponse application() {
        return new ApplicationResponse(
                UUID.randomUUID(), "SOL-0001", UUID.randomUUID(), "PERSONAL_LOAN", "AUTO_APPROVED",
                new BigDecimal("50000"), 12,
                "APPROVED", "AUTOMATIC", "BAJO", null, null,
                null, null, null, null, null, Instant.now());
    }
}
