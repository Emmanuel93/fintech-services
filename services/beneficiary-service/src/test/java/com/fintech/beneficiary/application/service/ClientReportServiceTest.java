package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.ClientNotYoursException;
import com.fintech.beneficiary.domain.PlacementValidationException;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ClientReportServiceTest {

    @Mock CreditPortfolioClient portfolio;
    @Mock PlacementRepository placements;

    ClientReportService service;

    final UUID her = UUID.randomUUID();
    final UUID other = UUID.randomUUID();
    final UUID client = UUID.randomUUID();
    final UUID line = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ClientReportService(portfolio, placements);
        lenient().when(placements.findByDistributorPartyIdOrderByCreatedAtDesc(her)).thenReturn(List.of());
        lenient().when(portfolio.accountsOf(her)).thenReturn(List.of(account()));
        lenient().when(portfolio.findActiveDistributorLine(her)).thenReturn(Optional.of(account()));
    }

    CreditPortfolioClient.CreditAccount account() {
        return new CreditPortfolioClient.CreditAccount(line, "DL-002", "DISTRIBUTOR_LINE", "REVOLVING", her, "ACTIVE",
                new BigDecimal("0.95"), new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000"),
                BigDecimal.ZERO, null, 0, null, null, null, null, null, null, null, false, null, BigDecimal.ZERO,
                new BigDecimal("0.18"), "NETTED");
    }

    void placedOnHerLine() {
        given(portfolio.profile(client, her)).willReturn(Optional.of(new CreditPortfolioClient.ClientProfile(client, "NUEVO",
                List.of(), 1, 0, 1, new BigDecimal("3000"),
                List.of(new CreditPortfolioClient.LineStanding(line, her, 1, new BigDecimal("3000"), false)))));
    }

    @Test
    @DisplayName("reporta a un cliente que colocó: la marca va a cartera con su motivo, sus comentarios y su línea")
    void sheReportsAClientShePlaced() {
        placedOnHerLine();
        var flag = new CreditPortfolioClient.StoredFlag(UUID.randomUUID(), client, "DISTRIBUTOR_REPORT", "DISTRIBUTOR", her, line,
                "VOUCHER_UNPAID", "Le cubrí dos quincenas", Instant.now(), her.toString(), null, null, null, true);
        given(portfolio.report(client, her, line, "VOUCHER_UNPAID", "Le cubrí dos quincenas")).willReturn(flag);

        assertThat(service.report(her, client, "VOUCHER_UNPAID", "Le cubrí dos quincenas")).isEqualTo(flag);
    }

    @Test
    @DisplayName("no puede reportar a quien no colocó, ni con un motivo fuera del catálogo, ni «otro» sin contar qué pasó")
    void sheCannotReportStrangersNorWithoutAReason() {
        given(portfolio.profile(client, her)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.report(her, client, "REFUSES_TO_PAY", null)).isInstanceOf(ClientNotYoursException.class);
        assertThatThrownBy(() -> service.report(her, client, "LE_CAE_MAL", null)).isInstanceOf(PlacementValidationException.class);
        assertThatThrownBy(() -> service.report(her, client, "OTHER", " ")).isInstanceOf(PlacementValidationException.class);
        then(portfolio).should(never()).report(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("levanta sólo sus propios reportes")
    void sheClearsOnlyHerOwnReports() {
        UUID mine = UUID.randomUUID(), theirs = UUID.randomUUID();
        given(portfolio.flags(client, her)).willReturn(List.of(
                new CreditPortfolioClient.StoredFlag(mine, client, "DISTRIBUTOR_REPORT", "DISTRIBUTOR", her, line, "NOT_LOCATABLE",
                        "No contesta", Instant.now(), her.toString(), null, null, null, true),
                new CreditPortfolioClient.StoredFlag(theirs, client, "DISTRIBUTOR_REPORT", "DISTRIBUTOR", other, UUID.randomUUID(),
                        "REFUSES_TO_PAY", "x", Instant.now(), other.toString(), null, null, null, true)));

        service.clear(her, client, mine, "Ya la localicé");
        then(portfolio).should().clearFlag(client, mine, her, "Ya la localicé");

        assertThatThrownBy(() -> service.clear(her, client, theirs, "no es mía")).isInstanceOf(ClientNotYoursException.class);
        then(portfolio).should(never()).clearFlag(eq(client), eq(theirs), any(), any());
    }

    @Test
    @DisplayName("los límites de su cliente: ve sus marcas; la de otra distribuidora sólo le dice que Kredius no le presta")
    void limitsShowOnlyHerMarks() {
        var hers = new CreditPortfolioClient.ClientFlag(UUID.randomUUID(), "DISTRIBUTOR_REPORT", "DISTRIBUTOR", Instant.now(), her, line,
                "NOT_LOCATABLE", "No contesta");
        var theirs = new CreditPortfolioClient.ClientFlag(UUID.randomUUID(), "DISTRIBUTOR_REPORT", "DISTRIBUTOR", Instant.now(), other,
                UUID.randomUUID(), "REFUSES_TO_PAY", "Comentario de otra");
        given(portfolio.placementLimits(line, client, new BigDecimal("3000"), 12, her)).willReturn(Optional.of(
                new CreditPortfolioClient.PlacementLimits(client, "NUEVO", true, "S_TIJ",
                        new CreditPortfolioClient.Limits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16), 0, BigDecimal.ZERO,
                        new BigDecimal("5000"), 1, List.of(hers, theirs), false, "CLIENT_RED_LISTED: DISTRIBUTOR_REPORT")));

        var view = service.limits(her, client, new BigDecimal("3000"), 12);

        assertThat(view.canPlace()).isFalse();
        assertThat(view.canReceivePlacements()).isFalse();
        assertThat(view.warnings()).containsExactly(hers);
        assertThat(view.message()).isEqualTo("Por ahora Kredius no puede prestarle a este cliente");
        assertThat(view.clientType()).isEqualTo("NUEVO");
    }
}
