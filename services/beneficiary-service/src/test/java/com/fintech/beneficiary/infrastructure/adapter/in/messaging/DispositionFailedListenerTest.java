package com.fintech.beneficiary.infrastructure.adapter.in.messaging;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.application.port.out.PlacementRepository;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.DispositionFailedListener.DispositionFailedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A placement whose SPEI to the beneficiary could not happen (D63) ends FAILED with its reason: the one in flight on
 * the distributor's line, by its disposition or its amount. Whatever else the portfolio's event says is left alone.
 */
class DispositionFailedListenerTest {

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID LINE = UUID.randomUUID();

    private PlacementRepository placements;
    private PlacementLifecycleUseCase lifecycle;
    private DispositionFailedListener listener;

    @BeforeEach
    void setUp() {
        placements = mock(PlacementRepository.class);
        lifecycle = mock(PlacementLifecycleUseCase.class);
        listener = new DispositionFailedListener(placements, lifecycle);
    }

    private static Placement placement(PlacementStatus status, UUID line, String amount, UUID dispositionId) {
        Placement p = mock(Placement.class);
        given(p.getPlacementId()).willReturn(UUID.randomUUID());
        given(p.getStatus()).willReturn(status);
        given(p.getDistributorCreditAccountId()).willReturn(line);
        given(p.getAmount()).willReturn(new BigDecimal(amount));
        given(p.getDispositionId()).willReturn(dispositionId);
        return p;
    }

    private static DispositionFailedPayload failed(UUID disposition, String type, String amount, String outcome) {
        return new DispositionFailedPayload(disposition, LINE, DISTRIBUTOR, type, new BigDecimal(amount), outcome,
                "REJECTED_BY_PROVIDER");
    }

    @Test
    @DisplayName("la colocación en vuelo de esa línea y ese monto termina fallida; la ya depositada no se toca")
    void theOneInFlightFails() {
        Placement deposited = placement(PlacementStatus.DISBURSED, LINE, "15000.02", UUID.randomUUID());
        Placement inFlight = placement(PlacementStatus.DISBURSING, LINE, "15000.02", UUID.randomUUID());
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(deposited, inFlight));

        UUID inFlightId = inFlight.getPlacementId();
        UUID depositedId = deposited.getPlacementId();

        listener.onDispositionFailed(failed(UUID.randomUUID(), "THIRD_PARTY_CREDIT", "15000.02", "FAILED"));

        verify(lifecycle).fail(eq(inFlightId), contains("no se realizó"));
        verify(lifecycle, never()).fail(eq(depositedId), any());
    }

    @Test
    @DisplayName("la disposición la nombra antes que el monto, y una devolución dice que la devolvió el banco")
    void theDispositionNamesItFirst() {
        UUID disposition = UUID.randomUUID();
        Placement sameAmount = placement(PlacementStatus.DISBURSING, LINE, "9000.00", UUID.randomUUID());
        Placement named = placement(PlacementStatus.DISBURSING, LINE, "8000.00", disposition);
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(named, sameAmount));

        UUID namedId = named.getPlacementId();

        listener.onDispositionFailed(failed(disposition, "THIRD_PARTY_CREDIT", "9000.00", "RETURNED"));

        verify(lifecycle).fail(eq(namedId), contains("devolvió"));
    }

    @Test
    @DisplayName("una disposición que no es colocación (préstamo propio) o de otra línea no toca ninguna colocación")
    void othersAreLeftAlone() {
        Placement otherLine = placement(PlacementStatus.DISBURSING, UUID.randomUUID(), "5000.00", UUID.randomUUID());
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(List.of(otherLine));

        listener.onDispositionFailed(failed(UUID.randomUUID(), "SELF_USE", "5000.00", "FAILED"));
        listener.onDispositionFailed(failed(UUID.randomUUID(), "THIRD_PARTY_CREDIT", "5000.00", "FAILED"));

        verify(lifecycle, never()).fail(any(), any());
    }

    @Test
    @DisplayName("rendimiento: entre diez mil colocaciones de una distribuidora encuentra la suya en menos de 200 ms")
    void performance() {
        List<Placement> many = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            many.add(placement(PlacementStatus.DISBURSED, LINE, "1000.00", UUID.randomUUID()));
        }
        Placement inFlight = placement(PlacementStatus.DISBURSING, LINE, "15000.02", UUID.randomUUID());
        many.add(inFlight);
        UUID inFlightId = inFlight.getPlacementId();
        given(placements.findByDistributorPartyIdOrderByCreatedAtDesc(DISTRIBUTOR)).willReturn(many);

        long started = System.nanoTime();
        listener.onDispositionFailed(failed(UUID.randomUUID(), "THIRD_PARTY_CREDIT", "15000.02", "FAILED"));
        long ms = (System.nanoTime() - started) / 1_000_000;

        verify(lifecycle).fail(eq(inFlightId), any());
        assertThat(ms).as("10,001 colocaciones en %d ms", ms).isLessThan(200);
    }
}
