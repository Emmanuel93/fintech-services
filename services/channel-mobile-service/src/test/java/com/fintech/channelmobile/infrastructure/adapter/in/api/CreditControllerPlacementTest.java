package com.fintech.channelmobile.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.channelmobile.infrastructure.adapter.in.api.dto.DisposeRequest;
import com.fintech.channelmobile.infrastructure.adapter.out.client.BeneficiaryClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.CreditProductClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.PaymentsClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.ScoringClient;
import com.fintech.channelmobile.infrastructure.adapter.out.client.WalletClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

class CreditControllerPlacementTest {

    final CreditPortfolioClient portfolio = mock(CreditPortfolioClient.class);
    final WalletClient wallet = mock(WalletClient.class);
    final BeneficiaryClient beneficiaries = mock(BeneficiaryClient.class);
    final CreditController controller = new CreditController(mock(CreditProductClient.class), mock(OriginationClient.class),
            portfolio, wallet, mock(PaymentsClient.class), mock(ScoringClient.class), beneficiaries);
    final ObjectMapper json = new ObjectMapper();

    final UUID her = UUID.randomUUID();
    final UUID line = UUID.randomUUID();
    final UUID client = UUID.randomUUID();
    MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", her.toString());
        given(portfolio.getAccountsByPartyId(her, her.toString())).willReturn(List.of(line("DISTRIBUTOR_LINE")));
    }

    CreditPortfolioClient.CreditAccountResponse line(String productType) {
        return new CreditPortfolioClient.CreditAccountResponse(line, UUID.randomUUID(), "CTR-DL", "DL-002", productType, "REVOLVING",
                her, "ACTIVE", new BigDecimal("0.95"), new BigDecimal("0.36"), null, new BigDecimal("100000"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000"), BigDecimal.ZERO, null, "MEDIO", null, 0, null, null,
                null, null, null, null, null, null, null, null, false, null, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("si su cliente no puede recibir la colocación, se le dice en la puerta y no se pide nada a wallet")
    void aRefusedClientIsToldAtTheDoor() throws Exception {
        given(beneficiaries.placementLimits(her.toString(), client, new BigDecimal("8000.0"), 12)).willReturn(json.readTree(
                "{\"canPlace\":false,\"message\":\"A un cliente nuevo le puedes prestar hasta $5,000.00 por préstamo\"}"));

        assertThatThrownBy(() -> controller.dispose(new DisposeRequest(8000, client.toString(), 12), request))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("A un cliente nuevo le puedes prestar hasta $5,000.00 por préstamo");
                });
        then(wallet).should(never()).requestDisposition(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("si cabe, o si beneficiary no contesta, la colocación sigue y cartera decide")
    void aFittingClientOrNoAnswerGoesOn() throws Exception {
        given(beneficiaries.placementLimits(her.toString(), client, new BigDecimal("3000.0"), 12))
                .willReturn(json.readTree("{\"canPlace\":true}"));
        controller.dispose(new DisposeRequest(3000, client.toString(), 12), request);
        then(wallet).should().requestDisposition(eq(line), eq(her.toString()), any(), eq(client), eq(12));

        given(beneficiaries.placementLimits(her.toString(), client, new BigDecimal("2000.0"), 12))
                .willThrow(new IllegalStateException("beneficiary-service no respondió"));
        controller.dispose(new DisposeRequest(2000, client.toString(), 12), request);
        then(wallet).should().requestDisposition(eq(line), eq(her.toString()), eq(new BigDecimal("2000.0")), eq(client), eq(12));
    }

    @Test
    @DisplayName("una disposición sin cliente o en un producto que no es de distribuidora no pregunta límites")
    void noClientNoQuestion() {
        given(portfolio.getAccountsByPartyId(her, her.toString())).willReturn(List.of(line("REVOLVING_LINE")));
        controller.dispose(new DisposeRequest(3000, client.toString(), 12), request);
        then(beneficiaries).should(never()).placementLimits(any(), any(), any(), anyInt());
    }
}
