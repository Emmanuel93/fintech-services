package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PaymentsClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El invariante rector, probado en el punto donde más duele: el listado de
 * cartera resuelve los nombres de una página de 25 obligados <b>distintos</b> en
 * UNA llamada a party (batch), no en 25. Si esto se rompe, vuelve el N+1.
 */
class PortfolioControllerNamesTest {

    @Test
    @SuppressWarnings("unchecked")
    void list_resolvesObligorNamesInOneBatchCall_notPerRow() {
        CreditPortfolioClient portfolio = mock(CreditPortfolioClient.class);
        PartyClient party = mock(PartyClient.class);

        List<CreditAccountResponse> rows = new ArrayList<>();
        List<PartyResponse> parties = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            UUID obligor = UUID.randomUUID();           // cartera guarda el prospectId como obligado
            rows.add(account(obligor));
            parties.add(party(obligor, "Cliente" + i));
        }

        when(portfolio.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new CreditPortfolioClient.PageResponse<>(rows, 0, 25, 25, 1));
        when(party.batch(anyCollection(), eq("prospectId"))).thenReturn(parties);

        PortfolioController controller = new PortfolioController(portfolio, party, mock(PaymentsClient.class));
        ResponseEntity<Map<String, Object>> resp =
                controller.list(null, null, null, null, null, null, 0, 25, "createdAt,desc");

        // Una sola llamada a party para 25 filas; nada de una consulta por fila.
        verify(party, times(1)).batch(anyCollection(), eq("prospectId"));
        verify(party, never()).batch(anyCollection(), eq("partyId")); // todo resolvió por prospecto
        verify(party, never()).getByProspectId(any());
        verify(party, never()).getByPartyId(any());

        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        assertThat(content).hasSize(25);
        assertThat(content).allSatisfy(r -> assertThat(r.get("obligorName")).isNotNull());
    }

    private static CreditAccountResponse account(UUID obligor) {
        return new CreditAccountResponse(
                UUID.randomUUID(), "CTR-1", obligor, "PL-001", "PERSONAL_LOAN", "INSTALLMENT", "ACTIVE",
                null, null, new BigDecimal("1000"), null, null, null, null,
                null, 0, "BAJO", null, null, null,
                null, null, null,
                null, null, null,
                null, null);
    }

    private static PartyResponse party(UUID prospectId, String firstName) {
        return new PartyResponse(
                UUID.randomUUID(), prospectId, null, "INDIVIDUAL", "ACTIVE",
                firstName, "Apellido1", "Apellido2", null, null, null, null, null, null, null, null);
    }
}
