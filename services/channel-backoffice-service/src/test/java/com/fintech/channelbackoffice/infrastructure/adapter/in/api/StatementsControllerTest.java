package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StatementClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StatementClient.StatementDocument;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.StatementClient.StatementResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The statements from a client's or a distributor's file: what is listed, who can download it, and that a
 * statement is only handed over from the file of its own holder.
 */
@ExtendWith(MockitoExtension.class)
class StatementsControllerTest {

    private static final String STAFF = "6b1f8c3e-0000-4000-8000-000000000001";

    @Mock PartyClient partyClient;
    @Mock CreditPortfolioClient creditPortfolioClient;
    @Mock StatementClient statementClient;
    @Mock StaffIdentityResolver staffIdentities;

    StatementsController controller;

    private final UUID partyId = UUID.randomUUID();
    private final UUID prospectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new StatementsController(partyClient, creditPortfolioClient, statementClient, staffIdentities);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(STAFF, null, List.of()));
    }

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    private void internalStaff() {
        given(staffIdentities.resolve(STAFF)).willReturn(Optional.of(
                new StaffIdentityResolver.StaffIdentity("ops@kredius.mx", "Olivia Paz", null, "INTERNO")));
    }

    private void party(UUID prospect) {
        given(partyClient.getByPartyId(partyId)).willReturn(new PartyClient.PartyResponse(partyId, prospect, null,
                "INDIVIDUAL", "ACTIVE", "Rocío", "Uribe", "Herrera", null, null, null, null, null, null, null, null));
    }

    private static CreditAccountResponse account(UUID id, String contract, String productType) {
        return new CreditAccountResponse(id, contract, UUID.randomUUID(), "P-1", productType, "REVOLVING", "ACTIVE",
                null, null, BigDecimal.ZERO, null, null, null, null, null, 0, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static StatementResponse statement(UUID account, int cycle, LocalDate cutoff, String status) {
        return new StatementResponse(UUID.randomUUID(), account, cycle, "CREDIT_CARD", "REVOLVING",
                cutoff.minusMonths(1).plusDays(1), cutoff, cutoff.plusDays(20), new BigDecimal("7586.36"),
                new BigDecimal("7586.36"), new BigDecimal("597.29"), status, null, 41_000L, Instant.now());
    }

    @Test
    @DisplayName("lists the statements of every account of the party, newest cutoff first, and only offers generated ones")
    void listsEveryAccountNewestFirst() {
        internalStaff();
        party(prospectId);
        UUID card = UUID.randomUUID(), line = UUID.randomUUID();
        // The app onboarding keys the card by prospectId; the console keys the line by partyId.
        given(creditPortfolioClient.listByParty(prospectId)).willReturn(List.of(account(card, "CTR-CARD", "CREDIT_CARD")));
        given(creditPortfolioClient.listByParty(partyId)).willReturn(List.of(account(line, "CTR-LINE", "DISTRIBUTOR_LINE")));
        given(statementClient.byAccount(card)).willReturn(List.of(
                statement(card, 2, LocalDate.of(2026, 5, 4), "GENERATED"),
                statement(card, 1, LocalDate.of(2026, 4, 2), "GENERATED")));
        given(statementClient.byAccount(line)).willReturn(List.of(statement(line, 1, LocalDate.of(2026, 4, 13), "FAILED")));

        Map<String, Object> body = controller.list(partyId).getBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) body.get("statements");
        assertThat(statements).extracting(s -> s.get("cutoffDate"))
                .containsExactly(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 4, 13), LocalDate.of(2026, 4, 2));
        assertThat(statements).extracting(s -> s.get("contractNumber"))
                .containsExactly("CTR-CARD", "CTR-LINE", "CTR-CARD");
        assertThat(statements).extracting(s -> s.get("downloadable")).containsExactly(true, false, true);
        assertThat((List<?>) body.get("accounts")).hasSize(2);
    }

    @Test
    @DisplayName("one call to statement-service per account, whatever the number of statements")
    void callsScaleWithAccountsNotStatements() {
        internalStaff();
        party(null);
        UUID line = UUID.randomUUID();
        given(creditPortfolioClient.listByParty(partyId)).willReturn(List.of(account(line, "CTR-LINE", "DISTRIBUTOR_LINE")));
        List<StatementResponse> many = new ArrayList<>();
        for (int i = 1; i <= 60; i++) many.add(statement(line, i, LocalDate.of(2026, 1, 1).plusDays(14L * i), "GENERATED"));
        given(statementClient.byAccount(line)).willReturn(many);

        controller.list(partyId);

        verify(statementClient, times(1)).byAccount(line);
        verify(statementClient, never()).get(any());
        verify(statementClient, never()).pdf(any());
    }

    @Test
    @DisplayName("the PDF of one of the party's statements is delivered as a download, not cached")
    void deliversOwnStatementAsDownload() {
        internalStaff();
        party(prospectId);
        UUID card = UUID.randomUUID();
        given(creditPortfolioClient.listByParty(prospectId)).willReturn(List.of(account(card, "CTR-CARD", "CREDIT_CARD")));
        given(creditPortfolioClient.listByParty(partyId)).willReturn(List.of());
        StatementResponse s = statement(card, 3, LocalDate.of(2026, 6, 2), "GENERATED");
        given(statementClient.get(s.statementId())).willReturn(s);
        given(statementClient.pdf(s.statementId())).willReturn(new StatementDocument(new byte[]{'%', 'P', 'D', 'F'},
                "application/pdf", "estado-de-cuenta-CTR-CARD-2026-06-02.pdf"));

        var response = controller.pdf(partyId, s.statementId(), true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment").contains("estado-de-cuenta-CTR-CARD-2026-06-02.pdf");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("application/pdf");
        assertThat(response.getBody()).startsWith('%', 'P', 'D', 'F');

        assertThat(controller.pdf(partyId, s.statementId(), false).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("inline");
    }

    @Test
    @DisplayName("another holder's statement is not handed over from this file: 404, and its PDF is never fetched")
    void anotherHoldersStatementIsNotFound() {
        internalStaff();
        party(prospectId);
        given(creditPortfolioClient.listByParty(prospectId)).willReturn(List.of(account(UUID.randomUUID(), "CTR-MINE", "CREDIT_CARD")));
        given(creditPortfolioClient.listByParty(partyId)).willReturn(List.of());
        StatementResponse foreign = statement(UUID.randomUUID(), 1, LocalDate.of(2026, 4, 2), "GENERATED");
        given(statementClient.get(foreign.statementId())).willReturn(foreign);

        assertThatThrownBy(() -> controller.pdf(partyId, foreign.statementId(), true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(statementClient, never()).pdf(any());
    }

    @Test
    @DisplayName("a distributor's employee gets neither the list nor the PDF: the statement carries RFC and address")
    void externalCollaboratorIsDenied() {
        given(staffIdentities.resolve(STAFF)).willReturn(Optional.of(
                new StaffIdentityResolver.StaffIdentity("colab@distribuidora.mx", "Colaborador", null, "COLABORADOR_EMPRESARIAL")));

        assertThatThrownBy(() -> controller.list(partyId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> controller.pdf(partyId, UUID.randomUUID(), true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(statementClient, never()).byAccount(any());
        verify(statementClient, never()).pdf(any());
    }

    @Test
    @DisplayName("when it cannot be told whose employee is asking, nothing is handed over")
    void unknownEmployerIsDenied() {
        given(staffIdentities.resolve(STAFF)).willReturn(Optional.empty());

        assertThatThrownBy(() -> controller.list(partyId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(partyClient, never()).getByPartyId(any());
    }

    @Test
    @DisplayName("a distributor's file sends its prospectId (the sales-org partyRef): the holder is found by prospect")
    void distributorIdIsResolvedAsProspect() {
        internalStaff();
        UUID realPartyId = UUID.randomUUID();
        given(partyClient.getByPartyId(prospectId)).willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "party-service"));
        given(partyClient.getByProspectId(prospectId)).willReturn(new PartyClient.PartyResponse(realPartyId, prospectId, null,
                "INDIVIDUAL", "PROSPECT", "Alejandra", "Bustamante", "Montalvo", null, null, null, null, null, null, null, null));
        UUID line = UUID.randomUUID();
        given(creditPortfolioClient.listByParty(prospectId)).willReturn(List.of(account(line, "CTR-LINE", "DISTRIBUTOR_LINE")));
        given(creditPortfolioClient.listByParty(realPartyId)).willReturn(List.of());
        given(statementClient.byAccount(line)).willReturn(List.of(statement(line, 1, LocalDate.of(2026, 4, 13), "GENERATED")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statements = (List<Map<String, Object>>) controller.list(prospectId).getBody().get("statements");

        assertThat(statements).hasSize(1);
    }

    @Test
    @DisplayName("an id that is neither a party nor a prospect is 404")
    void unknownHolderIsNotFound() {
        internalStaff();
        given(partyClient.getByPartyId(partyId)).willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "party-service"));
        given(partyClient.getByProspectId(partyId)).willReturn(null);

        assertThatThrownBy(() -> controller.list(partyId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
