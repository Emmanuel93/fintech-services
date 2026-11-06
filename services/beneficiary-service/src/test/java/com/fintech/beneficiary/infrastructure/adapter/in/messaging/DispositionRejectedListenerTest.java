package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.DispositionRejectedListener.DispositionRejectedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.never;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * A placement the portfolio refused (the line blocked by arrears, an amount or a term outside the product) ends FAILED
 * with the reason in the distributor's words: the one in flight on her line and for that amount, nothing else.
 */
class DispositionRejectedListenerTest {

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

    private PlacementRepository placements;
    private PlacementLifecycleUseCase lifecycle;
    private DispositionRejectedListener listener;

    @BeforeEach
    void setUp() {
        placements = mock(PlacementRepository.class);
        lifecycle = mock(PlacementLifecycleUseCase.class);
        listener = new DispositionRejectedListener(placements, lifecycle);
    }

    private static Placement placement(PlacementStatus status, UUID line, String amount) {
        Placement p = mock(Placement.class);
        given(p.getPlacementId()).willReturn(UUID.randomUUID());
        given(p.getStatus()).willReturn(status);
        given(p.getDistributorCreditAccountId()).willReturn(line);
        given(p.getAmount()).willReturn(new BigDecimal(amount));
        return p;
    }

    private static DispositionRejectedPayload rejected(String amount, String reason) {
        return new DispositionRejectedPayload(UUID.randomUUID().toString(), "evt-" + UUID.randomUUID(), LINE, DISTRIBUTOR,
                new BigDecimal(amount), reason);
    }

    @Test
    @DisplayName("la colocación en vuelo de esa línea y ese monto falla con el motivo en palabras de la distribuidora")
    void theOneInFlightFailsWithTheReason() {
        Placement deposited = placement(PlacementStatus.DISBURSED, LINE, "5000.00");
        Placement inFlight = placement(PlacementStatus.DISBURSING, LINE, "5000.00");
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(deposited, inFlight));

        listener.onDispositionRejected(rejected("5000.00", "LINE_BLOCKED_IN_ARREARS: 1 statement(s) unpaid past due"));

        then(lifecycle).should().fail(eq(inFlight.getPlacementId()),
                eq("Tu línea está bloqueada por atraso: paga lo vencido para volver a colocar"));
        then(lifecycle).should(never()).fail(eq(deposited.getPlacementId()), anyString());
    }

    @Test
    @DisplayName("sin colocación en vuelo que la reclame, el rechazo no toca nada")
    void nothingInFlightNothingFails() {
        Placement deposited = placement(PlacementStatus.DISBURSED, LINE, "5000.00");
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(deposited));
        listener.onDispositionRejected(rejected("5000.00", "TERM_BELOW_MINIMUM: term=8 min=12"));
        then(lifecycle).should(never()).fail(any(), anyString());
    }

    @Test
    @DisplayName("cada motivo de cartera se traduce; uno desconocido se cita tal cual")
    void reasonsInHerWords() {
        assertThat(DispositionRejectedListener.reasonFor("TERM_ABOVE_MAXIMUM: term=26 max=24"))
                .isEqualTo("El plazo no está dentro de lo que permite el producto");
        assertThat(DispositionRejectedListener.reasonFor("AMOUNT_BELOW_MINIMUM: amount=500 min=1000"))
                .isEqualTo("El monto no está dentro de lo que permite el producto");
        assertThat(DispositionRejectedListener.reasonFor("INSUFFICIENT_AVAILABLE_CREDIT: amount=9000 availableCredit=100"))
                .isEqualTo("Tu línea no tiene disponible suficiente");
        assertThat(DispositionRejectedListener.reasonFor("LINE_CLOSED_AFTER_QUITA: convenio de quita del 2026-06-15"))
                .isEqualTo("Tu línea quedó cerrada por el convenio de quita: ya no coloca");
        assertThat(DispositionRejectedListener.reasonFor("AML_RECEPTOR: listas X")).contains("listas");
        assertThat(DispositionRejectedListener.reasonFor("SOMETHING_ELSE")).contains("SOMETHING_ELSE");
    }

    @Test
    @DisplayName("rendimiento: diez mil rechazos se atienden en menos de un segundo")
    void tenThousandRejectionsInUnderASecond() {
        Placement inFlight = placement(PlacementStatus.DISBURSING, LINE, "5000.00");
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(inFlight));
        long start = System.nanoTime();
        for (int n = 0; n < 10_000; n++) {
            listener.onDispositionRejected(rejected("5000.00", "LINE_BLOCKED_IN_ARREARS"));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertThat(ms).as("10,000 rejections took %d ms", ms).isLessThan(1_000);
    }

    @Test
    @DisplayName("los motivos del lado del cliente se dicen en sus palabras, antes que los del producto")
    void clientSideReasonsInHerWords() {
        assertThat(DispositionRejectedListener.reasonFor("AMOUNT_ABOVE_CLIENT_LIMIT: cliente NUEVO hasta 5000 por préstamo, pidió 8000"))
                .isEqualTo("A un cliente nuevo le puedes prestar hasta $5,000.00 por préstamo");
        assertThat(DispositionRejectedListener.reasonFor("TERM_ABOVE_CLIENT_LIMIT: cliente REVOLVENTE hasta 20 quincenas, pidió 24"))
                .isEqualTo("A un cliente revolvente le puedes prestar a lo más a 20 quincenas");
        assertThat(DispositionRejectedListener.reasonFor("CLIENT_RED_LISTED: DISTRIBUTOR_REPORT"))
                .isEqualTo("Por ahora Kredius no puede prestarle a este cliente");
        // The product's own codes keep their words.
        assertThat(DispositionRejectedListener.reasonFor("AMOUNT_ABOVE_MAXIMUM: 12000 > 10000"))
                .isEqualTo("El monto no está dentro de lo que permite el producto");
    }
}
