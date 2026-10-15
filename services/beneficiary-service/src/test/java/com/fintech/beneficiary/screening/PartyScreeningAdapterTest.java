package com.fintech.beneficiary.screening;

import com.fintech.beneficiary.domain.ScreeningVerdict;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyScreeningAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * El adaptador que trae el veredicto de listas, y lo que hace cuando <b>no puede</b>.
 *
 * <p>Toda la carga de estas pruebas está en el lado triste. El camino feliz es una línea; lo que
 * decide si el control sirve es qué devuelve cuando party no contesta, cuando contesta a medias, o
 * cuando la colocación no tiene a quién consultar — porque son los tres casos en los que un control
 * mal escrito deja pasar sin que nadie se entere.
 */
class PartyScreeningAdapterTest {

    private static final UUID PROSPECT = UUID.randomUUID();

    private static PartyClient.PartyResponse record(String screening, String motivo) {
        return new PartyClient.PartyResponse(UUID.randomUUID(), PROSPECT, "María", "Cortés",
                "Hernández", "ACTIVE", "VERIFIED", screening, motivo);
    }

    private static PartyScreeningAdapter adapterReturning(Optional<PartyClient.PartyResponse> r) {
        PartyClient party = mock(PartyClient.class);
        given(party.findByProspect(any())).willReturn(r);
        return new PartyScreeningAdapter(party);
    }

    // ── Lo que deja pasar ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("party dice CLEARED y eso deja pasar")
    void clearedPasses() {
        ScreeningVerdict v = adapterReturning(Optional.of(record("CLEARED", null)))
                .screeningOf(PROSPECT);

        assertThat(v.allowsPlacement()).isTrue();
        assertThat(v.blockReason()).isNull();
    }

    // ── Lo que no, y es donde está el valor ──────────────────────────────────────────────────

    @Test
    @DisplayName("party no contesta: NO se pasa, y el motivo lo dice")
    void anunreachablePartyBlocks() {
        // `findByProspect` ya se traga su excepción y devuelve vacío. Eso es razonable para buscar
        // una ficha; aquí vacío significa «no sé si está en listas», que tiene que detener.
        ScreeningVerdict v = adapterReturning(Optional.empty()).screeningOf(PROSPECT);

        assertThat(v.allowsPlacement()).isFalse();
        assertThat(v.status()).isEqualTo(ScreeningVerdict.UNKNOWN);
        assertThat(v.blockReason()).contains("party no entregó la ficha");
    }

    @Test
    @DisplayName("party contesta SIN el campo de listas: tampoco se pasa")
    void amissingFieldBlocks() {
        // Es la versión vieja del servicio, o una persona que nunca se verificó. Las dos cosas
        // significan lo mismo aquí, y ninguna es «limpia». Un `null` leído como falso-negativo sería
        // exactamente el fallo-abierto que este control existe para no tener.
        ScreeningVerdict v = adapterReturning(Optional.of(record(null, null))).screeningOf(PROSPECT);

        assertThat(v.allowsPlacement()).isFalse();
        assertThat(v.blockReason()).contains("no trae estado de listas");
    }

    @Test
    @DisplayName("una colocación sin prospecto de beneficiaria no se consulta: se bloquea")
    void aplacementWithoutAprospectBlocks() {
        // No hay a quién haber comprobado. La beneficiaria ni completó su alta, así que afirmar que
        // está limpia sería afirmar algo sobre alguien que no existe todavía.
        ScreeningVerdict v = adapterReturning(Optional.of(record("CLEARED", null)))
                .screeningOf(null);

        assertThat(v.allowsPlacement()).isFalse();
        assertThat(v.blockReason()).contains("no tiene prospecto");
    }

    @Test
    @DisplayName("el motivo que escribió cumplimiento se respeta tal cual")
    void thecomplianceReasonIsPreserved() {
        // Es el que tiene el detalle útil («nació en 1985, el de la lista en 1962») y el que permite
        // que la pantalla diga algo accionable en vez de un código.
        ScreeningVerdict v = adapterReturning(
                Optional.of(record("POTENTIAL_MATCH", "coincide con OFAC-SDN por nombre y fecha")))
                .screeningOf(PROSPECT);

        assertThat(v.blockReason()).isEqualTo("coincide con OFAC-SDN por nombre y fecha");
    }

    @Test
    @DisplayName("si cumplimiento no dejó motivo, se construye uno que al menos nombre el estado")
    void areasonIsAlwaysProvided() {
        // Un rechazo sin explicación obliga a quien lo recibe a adivinar, y lo que adivine va a ser
        // «es un error del sistema» — que es lo contrario de lo que pasó.
        ScreeningVerdict v = adapterReturning(Optional.of(record("CONFIRMED_MATCH", "  ")))
                .screeningOf(PROSPECT);

        assertThat(v.blockReason()).contains("CONFIRMED_MATCH");
    }
}
