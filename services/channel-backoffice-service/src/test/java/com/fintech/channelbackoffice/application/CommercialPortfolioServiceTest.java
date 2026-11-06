package com.fintech.channelbackoffice.application;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient.PartyResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The commercial rollup asks the portfolio for the accounts of every obligor with an executive. The ids travel
 * in the query string: with the 158 clients of the 2 Nov demo the request line overflowed Tomcat's header limit,
 * the portfolio answered a bare 400 and the whole commercial tree showed zero.
 */
class CommercialPortfolioServiceTest {

    @Test
    @DisplayName("the accounts of many obligors are fetched in batches small enough for the request line, and summed whole")
    void fetchesAccountsInBatches() {
        UUID root = UUID.randomUUID();
        UUID exec = UUID.randomUUID();
        SalesOrgClient salesOrg = mock(SalesOrgClient.class);
        PartyClient party = mock(PartyClient.class);
        CreditPortfolioClient portfolio = mock(CreditPortfolioClient.class);
        when(salesOrg.subtree(root)).thenReturn(List.of(Map.of("unitId", root.toString(), "name", "MX")));

        List<PartyResponse> clientes = new ArrayList<>();
        for (int i = 0; i < 158; i++) {
            clientes.add(party(UUID.randomUUID(), exec));
        }
        when(party.search(isNull(), isNull(), isNull(), isNull(), eq(0), anyInt(), anyString()))
                .thenReturn(new PartyClient.PageResponse<>(clientes.subList(0, 100), 0, 100, 158, 2));
        when(party.search(isNull(), isNull(), isNull(), isNull(), eq(1), anyInt(), anyString()))
                .thenReturn(new PartyClient.PageResponse<>(clientes.subList(100, 158), 1, 100, 158, 2));
        // One active account of 1,000 per obligor asked for, on the first page of each batch.
        when(portfolio.search(isNull(), isNull(), isNull(), isNull(), isNull(), anyCollection(), anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    Collection<UUID> ids = inv.getArgument(5);
                    int page = inv.getArgument(6);
                    List<CreditAccountResponse> cuentas = page == 0
                            ? ids.stream().map(id -> account(id, "1000.00")).toList()
                            : List.<CreditAccountResponse>of();
                    return new CreditPortfolioClient.PageResponse<>(cuentas, page, 100, ids.size(), 1);
                });
        List<Map<String, Object>> asignaciones = List.of(
                Map.of("assigneeType", "STAFF", "assigneeId", exec.toString(), "unitId", root.toString()));

        var rollup = new CommercialPortfolioService(salesOrg, party, portfolio).rollup(root, asignaciones, Set.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> lotes = ArgumentCaptor.forClass(Collection.class);
        verify(portfolio, atLeast(4)).search(isNull(), isNull(), isNull(), isNull(), isNull(), lotes.capture(),
                anyInt(), anyInt(), anyString());
        assertThat(lotes.getAllValues()).allSatisfy(lote -> assertThat(lote.size()).isLessThanOrEqualTo(50));
        assertThat(lotes.getAllValues().stream().mapToInt(Collection::size).sum())
                .as("every obligor is asked for once").isEqualTo(158);
        assertThat(new BigDecimal(String.valueOf(rollup.total().get("principal"))))
                .isEqualByComparingTo("158000.00");
    }

    private static PartyResponse party(UUID prospectId, UUID executive) {
        return new PartyResponse(UUID.randomUUID(), prospectId, null, "PERSON", "ACTIVE", "Ana", "Pérez", null,
                null, null, null, null, null, executive, "Ejecutivo", null);
    }

    private static CreditAccountResponse account(UUID obligor, String principal) {
        return new CreditAccountResponse(UUID.randomUUID(), "CTR-1", obligor, "PL-IND-STD-V1", "PERSONAL_LOAN",
                "INSTALLMENT", "ACTIVE", null, null, new BigDecimal(principal), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, 0, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
