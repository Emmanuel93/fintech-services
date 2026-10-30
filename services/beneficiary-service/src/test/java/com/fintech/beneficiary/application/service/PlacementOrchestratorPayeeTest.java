package com.fintech.beneficiary.application.service;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementLimits;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * A nombre de quién pide la colocación el SPEI.
 *
 * <p>Pedía la disposición sólo con la CLABE de la beneficiaria, y cartera completaba nombre y RFC con
 * los de la cuenta — la distribuidora. Aquí se fija que la petición lleve los de ella.
 */
@ExtendWith(MockitoExtension.class)
class PlacementOrchestratorPayeeTest {

    @Mock PlacementLifecycleUseCase lifecycle;
    @Mock PlacementRepository placements;
    @Mock CreditPortfolioClient creditPortfolio;
    @Mock WalletClient wallet;
    @Mock PlacementInviteService inviteService;
    @Mock PartyClient party;

    PlacementOrchestrator orchestrator;

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID LINE        = UUID.randomUUID();
    private static final String CLABE     = "646180157000000004";
    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 24, 1);

    @BeforeEach
    void setUp() {
        orchestrator = new PlacementOrchestrator(lifecycle, placements, creditPortfolio, wallet,
                new BeneficiaryProperties(), inviteService, party);
    }

    /** Una colocación con el KYC cerrado: ya tiene prospecto, Party y CLABE. */
    private static Placement kycCompleted(String typedName, UUID prospectId, UUID partyId) {
        Placement p = Placement.draft(DISTRIBUTOR, LINE, "DL-DIST-STD-V1", typedName, "5544749756",
                "Clienta de mi tienda desde 2023", new BigDecimal("45000"), 24, new BigDecimal("2076.52"),
                VerificationMode.SELF_SERVICE_LINK, Instant.now().plus(7, ChronoUnit.DAYS), LIMITS, null);
        p.startKyc();
        p.completeKyc(prospectId, partyId, CLABE);
        return p;
    }

    private static PartyClient.PartyResponse ficha(UUID prospectId, String rfc, String curp) {
        return new PartyClient.PartyResponse(UUID.randomUUID(), prospectId, "Rosa Elena", "Ibarra",
                "Salgado", "PROSPECT", "VERIFIED", "CLEARED", null, rfc, curp);
    }

    @Test
    @DisplayName("la disposición se pide con el nombre verificado y el RFC de la beneficiaria")
    void pideConElNombreYRfcDeLaBeneficiaria() {
        UUID prospect = UUID.randomUUID(), partyId = UUID.randomUUID();
        Placement p = kycCompleted("rosa elena ibarra", prospect, partyId);
        given(lifecycle.approve(eq(p.getPlacementId()), eq(DISTRIBUTOR), anyBoolean(), any())).willReturn(p);
        given(party.findByProspect(prospect))
                .willReturn(Optional.of(ficha(prospect, "IASR900612B0B", "IASR900612MDFBAO56")));

        orchestrator.approve(p.getPlacementId(), DISTRIBUTOR, true);

        then(wallet).should().requestThirdPartyDisposition(LINE, DISTRIBUTOR, new BigDecimal("45000"),
                partyId, CLABE, "Rosa Elena Ibarra Salgado", "IASR900612B0B", 24);
    }

    @Test
    @DisplayName("sin RFC en la ficha, viaja la CURP")
    void sinRfcViajaLaCurp() {
        UUID prospect = UUID.randomUUID(), partyId = UUID.randomUUID();
        Placement p = kycCompleted("Rosa Elena Ibarra Salgado", prospect, partyId);
        given(lifecycle.approve(eq(p.getPlacementId()), eq(DISTRIBUTOR), anyBoolean(), any())).willReturn(p);
        given(party.findByProspect(prospect)).willReturn(Optional.of(ficha(prospect, " ", "IASR900612MDFBAO56")));

        orchestrator.approve(p.getPlacementId(), DISTRIBUTOR, true);

        then(wallet).should().requestThirdPartyDisposition(eq(LINE), eq(DISTRIBUTOR), any(), eq(partyId),
                eq(CLABE), eq("Rosa Elena Ibarra Salgado"), eq("IASR900612MDFBAO56"), eq(24));
    }

    @Test
    @DisplayName("si party no contesta, sale con el nombre capturado de ELLA y sin RFC — nunca con el de la distribuidora")
    void sinPartyCaeAlNombreCapturado() {
        UUID prospect = UUID.randomUUID(), partyId = UUID.randomUUID();
        Placement p = kycCompleted("Rosa Elena Ibarra Salgado", prospect, partyId);
        given(lifecycle.approve(eq(p.getPlacementId()), eq(DISTRIBUTOR), anyBoolean(), any())).willReturn(p);
        given(party.findByProspect(prospect)).willReturn(Optional.empty());

        orchestrator.approve(p.getPlacementId(), DISTRIBUTOR, true);

        then(wallet).should().requestThirdPartyDisposition(eq(LINE), eq(DISTRIBUTOR), any(), eq(partyId),
                eq(CLABE), eq("Rosa Elena Ibarra Salgado"), isNull(), eq(24));
    }

    @Test
    @DisplayName("varias aprobaciones a la vez: cada petición lleva a SU beneficiaria")
    void aprobacionesConcurrentes() throws Exception {
        int colocaciones = 8;
        List<Placement> todas = new ArrayList<>();
        for (int i = 0; i < colocaciones; i++) {
            UUID prospect = UUID.randomUUID();
            Placement p = kycCompleted("Beneficiaria " + i, prospect, UUID.randomUUID());
            todas.add(p);
            given(lifecycle.approve(eq(p.getPlacementId()), eq(DISTRIBUTOR), anyBoolean(), any())).willReturn(p);
            given(party.findByProspect(prospect)).willReturn(Optional.of(new PartyClient.PartyResponse(
                    UUID.randomUUID(), prospect, "Beneficiaria", String.valueOf(i), null,
                    "PROSPECT", "VERIFIED", "CLEARED", null, "RFC" + i, null)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(colocaciones);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<?>> tareas = new ArrayList<>();
        for (Placement p : todas) {
            tareas.add(pool.submit(() -> {
                salida.await();
                orchestrator.approve(p.getPlacementId(), DISTRIBUTOR, true);
                return null;
            }));
        }
        salida.countDown();
        for (Future<?> t : tareas) t.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        for (int i = 0; i < colocaciones; i++) {
            Placement p = todas.get(i);
            then(wallet).should().requestThirdPartyDisposition(eq(LINE), eq(DISTRIBUTOR), any(),
                    eq(p.getBeneficiaryPartyId()), eq(CLABE), eq("Beneficiaria " + i), eq("RFC" + i), eq(24));
        }
    }
}
