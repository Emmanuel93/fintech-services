package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementLimits;
import com.fintech.beneficiary.domain.PlacementValidationException;
import com.fintech.beneficiary.domain.VerificationMode;
import com.fintech.beneficiary.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.WalletClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class PlacementOrchestratorClientChecksTest {

    @Mock PlacementLifecycleUseCase lifecycle;
    @Mock PlacementRepository placements;
    @Mock CreditPortfolioClient creditPortfolio;
    @Mock WalletClient wallet;
    @Mock PlacementInviteService inviteService;
    @Mock PartyClient party;

    PlacementOrchestrator orchestrator;

    static final UUID DISTRIBUTOR = UUID.randomUUID();
    static final UUID LINE = UUID.randomUUID();
    static final UUID CLIENT = UUID.randomUUID();
    static final PlacementLimits LIMITS = new PlacementLimits(new BigDecimal("1000"), new BigDecimal("10000"), 500, 12, 24, 2);

    @BeforeEach
    void setUp() {
        orchestrator = new PlacementOrchestrator(lifecycle, placements, creditPortfolio, wallet, new BeneficiaryProperties(), inviteService, party);
    }

    static Placement verified(String amount) {
        Placement p = Placement.draft(DISTRIBUTOR, LINE, "DL-002", "Rosa Elena Ibarra Salgado", "5544749756", "Vecina",
                new BigDecimal(amount), 12, new BigDecimal("500"), VerificationMode.SELF_SERVICE_LINK,
                Instant.now().plus(7, ChronoUnit.DAYS), LIMITS, null);
        p.startKyc();
        p.completeKyc(UUID.randomUUID(), CLIENT, "646180157000000004");
        return p;
    }

    static CreditPortfolioClient.PlacementLimits refused(String reason) {
        return new CreditPortfolioClient.PlacementLimits(CLIENT, "NUEVO", true, "S_TIJ",
                new CreditPortfolioClient.Limits(new BigDecimal("5000"), new BigDecimal("5000"), 1, 16), 0, BigDecimal.ZERO,
                new BigDecimal("5000"), 1, List.of(), false, reason);
    }

    @Test
    @DisplayName("al aprobar, si el cliente no cabe en los límites de su tipo, la colocación falla con el motivo y no sale dinero")
    void approvalRefusedByTheClientsLimits() {
        Placement p = verified("8000");
        given(lifecycle.approve(eq(p.getPlacementId()), eq(DISTRIBUTOR), anyBoolean(), any())).willReturn(p);
        given(creditPortfolio.placementLimits(LINE, CLIENT, new BigDecimal("8000"), 12, DISTRIBUTOR)).willReturn(Optional.of(
                refused("AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 5000 por préstamo, pidió 8000")));

        assertThatThrownBy(() -> orchestrator.approve(p.getPlacementId(), DISTRIBUTOR, true))
                .isInstanceOf(PlacementValidationException.class)
                .hasMessage("A un cliente nuevo le puedes prestar hasta $5,000.00 por préstamo");
        then(lifecycle).should().fail(p.getPlacementId(), "A un cliente nuevo le puedes prestar hasta $5,000.00 por préstamo");
        then(wallet).should(never()).requestThirdPartyDisposition(any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a una clienta que ya colocó antes la rechaza en la puerta, sin volver a verificarla")
    void aKnownClientIsRefusedAtTheDoor() {
        Placement before = verified("3000");
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(before));
        given(creditPortfolio.findActiveDistributorLine(DISTRIBUTOR)).willReturn(Optional.of(new CreditPortfolioClient.CreditAccount(
                LINE, "DL-002", "DISTRIBUTOR_LINE", "REVOLVING", DISTRIBUTOR, "ACTIVE", new BigDecimal("0.95"),
                new BigDecimal("100000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("100000"), BigDecimal.ZERO, null, 0,
                null, null, null, null, null, null, null, false, null, BigDecimal.ZERO, new BigDecimal("0.18"), "NETTED")));
        given(creditPortfolio.placementLimits(LINE, CLIENT, new BigDecimal("2000"), 12, DISTRIBUTOR)).willReturn(Optional.of(
                refused("CLIENT_ACTIVE_LOANS_LIMIT: cliente NUEVO hasta 1 préstamos vivos, ya tiene 1")));

        assertThatThrownBy(() -> orchestrator.create(DISTRIBUTOR, "Rosa Elena Ibarra Salgado", "5544749756", "Vecina",
                new BigDecimal("2000"), 12))
                .isInstanceOf(PlacementValidationException.class)
                .hasMessage("Un cliente nuevo puede tener hasta 1 préstamo vivo contigo y ya tiene 1");
        then(lifecycle).should(never()).create(any());
    }
}
