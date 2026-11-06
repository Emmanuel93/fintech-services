package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ReceiptClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ReceiptClient.ReceiptDocument;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.ReceiptClient.ReceiptResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReceiptsControllerTest {

    private static final String STAFF = "6b1f8c3e-0000-4000-8000-000000000001";

    @Mock PartyClient partyClient;
    @Mock ReceiptClient receiptClient;
    @Mock CreditPortfolioClient portfolio;
    @Mock StaffIdentityResolver staffIdentities;

    ReceiptsController controller;

    final UUID partyId = UUID.randomUUID();
    final UUID prospectId = UUID.randomUUID();
    final UUID line = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new ReceiptsController(partyClient, receiptClient, portfolio, staffIdentities);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(STAFF, null, List.of()));
        lenient().when(partyClient.getByPartyId(partyId)).thenReturn(new PartyClient.PartyResponse(partyId, prospectId, null,
                "INDIVIDUAL", "ACTIVE", "Laura", "Méndez", "Ortiz", null, null, null, null, null, null, null, null));
    }

    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    void staff(String employeeType) {
        given(staffIdentities.resolve(STAFF)).willReturn(Optional.of(
                new StaffIdentityResolver.StaffIdentity("ops@kredius.mx", "Olivia Paz", null, employeeType)));
    }

    ReceiptResponse receipt(UUID client, UUID distributor, String folio, LocalDate cutoff) {
        return new ReceiptResponse(UUID.randomUUID(), line, 21, client, distributor, cutoff.minusDays(14), cutoff, cutoff.plusDays(13),
                "GENERATED", folio, "Rosa Elena Ibarra Salgado", "REVOLVENTE", 2, new BigDecimal("1337.45"), 1234566L,
                "646180157000000004", 72_000L, Instant.now());
    }

    @Test
    @DisplayName("el expediente de la distribuidora lista los recibos de sus clientes, el corte más reciente primero")
    void herFileListsHerClientsReceipts() {
        staff("INTERNO");
        UUID client = UUID.randomUUID();
        // The portfolio keys her by her prospect id: the receipts carry it as the distributor.
        given(receiptClient.byParty(prospectId)).willReturn(List.of(
                receipt(client, prospectId, "RC-20251006-1234566", LocalDate.of(2025, 10, 6)),
                receipt(client, prospectId, "RC-20251021-1234566", LocalDate.of(2025, 10, 21))));
        given(receiptClient.byParty(partyId)).willReturn(List.of());

        Map<String, Object> body = controller.receipts(partyId);

        assertThat(body.get("asDistributor")).isEqualTo(true);
        @SuppressWarnings("unchecked") List<ReceiptResponse> receipts = (List<ReceiptResponse>) body.get("receipts");
        assertThat(receipts).extracting(ReceiptResponse::folio).containsExactly("RC-20251021-1234566", "RC-20251006-1234566");
    }

    @Test
    @DisplayName("el PDF sólo sale desde el expediente de su cliente o de su distribuidora; nunca para un colaborador externo")
    void thePdfBelongsToItsFile() {
        staff("INTERNO");
        UUID client = UUID.randomUUID();
        ReceiptResponse mine = receipt(client, prospectId, "RC-20251021-1234566", LocalDate.of(2025, 10, 21));
        ReceiptResponse someoneElses = receipt(UUID.randomUUID(), UUID.randomUUID(), "RC-20251021-9999999", LocalDate.of(2025, 10, 21));
        given(receiptClient.get(mine.receiptId())).willReturn(mine);
        given(receiptClient.get(someoneElses.receiptId())).willReturn(someoneElses);
        given(receiptClient.pdf(mine.receiptId())).willReturn(new ReceiptDocument(new byte[]{37, 80, 68, 70}, "application/pdf"));

        var ok = controller.pdf(partyId, mine.receiptId(), true);
        assertThat(ok.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("recibo-RC-20251021-1234566.pdf");
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo("no-store");

        assertThatThrownBy(() -> controller.pdf(partyId, someoneElses.receiptId(), false))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(receiptClient, never()).pdf(someoneElses.receiptId());
    }

    @Test
    void anExternalCollaboratorDoesNotSeeReceipts() {
        staff("COLABORADOR_EMPRESARIAL");
        assertThatThrownBy(() -> controller.receipts(partyId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(receiptClient, never()).byParty(any());
    }

    @Test
    @DisplayName("perfil del cliente con su historial; marcar y levantar exigen motivo y comentario, y llevan quién lo hizo")
    void creditProfileAndTheRedList() {
        given(portfolio.beneficiaryProfile(prospectId)).willReturn(Map.of("type", "NUEVO", "redListed", true));
        given(portfolio.beneficiaryFlags(prospectId)).willReturn(List.of(Map.of("reason", "DISTRIBUTOR_REPORT", "active", true)));
        Map<String, Object> profile = controller.creditProfile(partyId);
        assertThat(profile).containsEntry("type", "NUEVO").containsKey("history");

        assertThatThrownBy(() -> controller.report(partyId, new ReceiptsController.RedListRequest("NOT_LOCATABLE", " ")))
                .isInstanceOf(ResponseStatusException.class);
        given(portfolio.reportBeneficiary(eq(prospectId), any())).willReturn(Map.of("active", true));
        controller.report(partyId, new ReceiptsController.RedListRequest("SUSPECTED_FRAUD", "Identidad prestada"));
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(portfolio).reportBeneficiary(eq(prospectId), sent.capture());
        assertThat(sent.getValue()).containsEntry("source", "BACKOFFICE").containsEntry("raisedBy", STAFF).containsEntry("reason", "SUSPECTED_FRAUD");

        UUID flag = UUID.randomUUID();
        assertThatThrownBy(() -> controller.clear(partyId, flag, new ReceiptsController.ClearRequest(null))).isInstanceOf(ResponseStatusException.class);
        controller.clear(partyId, flag, new ReceiptsController.ClearRequest("Se aclaró con cobranza"));
        verify(portfolio).clearBeneficiaryFlag(eq(prospectId), eq(flag), any());
    }
}
