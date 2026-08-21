package com.fintech.party.application.service;

import com.fintech.party.application.port.out.PartyRelationshipRepository;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyNotFoundException;
import com.fintech.party.domain.PartyRelationship;
import com.fintech.party.domain.PartyRelationshipType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El vínculo entre dos parties, que es lo que sostiene el B2B2C.
 *
 * <p>Cubre A13: el beneficiario queda con rastro de qué distribuidora lo capturó. La tabla existía
 * desde la migración 006 y no tenía servicio ni controlador, así que ese rastro estaba modelado y
 * era imposible de crear — y sin él no hay a quién preguntarle por un expediente mal tomado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PartyRelationshipServiceTest {

    @Mock PartyRelationshipRepository relationshipRepository;
    @Mock PartyRepository partyRepository;

    PartyRelationshipService service;

    private static final UUID DISTRIBUIDORA = UUID.randomUUID();
    private static final UUID BENEFICIARIO  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PartyRelationshipService(relationshipRepository, partyRepository);
        given(partyRepository.findById(any())).willReturn(Optional.of(any_party()));
        given(relationshipRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(relationshipRepository.findActive(any(), any(), any())).willReturn(Optional.empty());
    }

    // ── A13 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A13 · la distribuidora queda registrada como quien dio de alta al beneficiario")
    void a13_vinculoDistribuidoraBeneficiario() {
        PartyRelationship r = service.relate(
                DISTRIBUIDORA, BENEFICIARIO, PartyRelationshipType.BENEFICIARY, null);

        assertThat(r.getPartyId()).isEqualTo(DISTRIBUIDORA);
        assertThat(r.getRelatedPartyId()).isEqualTo(BENEFICIARIO);
        assertThat(r.getRelationshipType()).isEqualTo("BENEFICIARY");
        assertThat(r.isActive()).isTrue();
    }

    @Test
    @DisplayName("A13 · desde el beneficiario se puede llegar a su distribuidora")
    void a13_seRecorreEnLosDosSentidos() {
        PartyRelationship vinculo = PartyRelationship.create(
                DISTRIBUIDORA, BENEFICIARIO, "BENEFICIARY", null);
        given(relationshipRepository.findActiveByRelatedPartyId(BENEFICIARIO))
                .willReturn(List.of(vinculo));

        // Sin esta dirección, «¿de qué distribuidora es este cliente?» —la pregunta que se hace
        // desde su ficha— obligaría a barrer todas las distribuidoras.
        assertThat(service.incoming(BENEFICIARIO))
                .singleElement()
                .extracting(PartyRelationship::getPartyId)
                .isEqualTo(DISTRIBUIDORA);
    }

    // ── Reglas ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dar de alta dos veces al mismo beneficiario es un reintento, no dos vínculos")
    void esIdempotente() {
        PartyRelationship existente = PartyRelationship.create(
                DISTRIBUIDORA, BENEFICIARIO, "BENEFICIARY", null);
        given(relationshipRepository.findActive(DISTRIBUIDORA, BENEFICIARIO, "BENEFICIARY"))
                .willReturn(Optional.of(existente));

        PartyRelationship r = service.relate(
                DISTRIBUIDORA, BENEFICIARIO, PartyRelationshipType.BENEFICIARY, null);

        assertThat(r).isSameAs(existente);
        verify(relationshipRepository, never()).save(any());
    }

    @Test
    @DisplayName("un party no se puede relacionar consigo mismo")
    void rechazaElAutoVinculo() {
        assertThatThrownBy(() -> service.relate(
                DISTRIBUIDORA, DISTRIBUIDORA, PartyRelationshipType.BENEFICIARY, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("se comprueban los dos extremos, y el error dice cuál falta")
    void rechazaPartyInexistente() {
        // Sin esta comprobación el fallo saldría como un 500 de llave foránea, que no dice cuál de
        // los dos no existe.
        given(partyRepository.findById(BENEFICIARIO)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.relate(
                DISTRIBUIDORA, BENEFICIARIO, PartyRelationshipType.BENEFICIARY, null))
                .isInstanceOf(PartyNotFoundException.class);
    }

    @Test
    @DisplayName("cerrar el vínculo lo deja con fecha de fin, no lo borra")
    void cerrarConservaElHistorico() {
        PartyRelationship vinculo = PartyRelationship.create(
                DISTRIBUIDORA, BENEFICIARIO, "BENEFICIARY", null);
        given(relationshipRepository.findById(any())).willReturn(Optional.of(vinculo));

        assertThat(service.end(vinculo.getRelationshipId())).isTrue();

        // Borrarlo dejaría colocaciones pasadas sin explicación de a quién se le hicieron.
        assertThat(vinculo.isActive()).isFalse();
        assertThat(vinculo.getEndedAt()).isNotNull();
        verify(relationshipRepository).save(vinculo);
    }

    @Test
    @DisplayName("cerrar un vínculo ya cerrado no es un error, es un no-op")
    void cerrarDosVecesEsInocuo() {
        PartyRelationship vinculo = PartyRelationship.create(
                DISTRIBUIDORA, BENEFICIARIO, "BENEFICIARY", null);
        vinculo.end();
        given(relationshipRepository.findById(any())).willReturn(Optional.of(vinculo));

        assertThat(service.end(vinculo.getRelationshipId())).isFalse();
    }

    private static Party any_party() {
        return Party.create(
                UUID.randomUUID(), UUID.randomUUID(), com.fintech.party.domain.PartyType.INDIVIDUAL,
                "Ana", "Pérez", "López", "PELA900101MDFRPN01", "PELA900101AB1",
                java.time.LocalDate.of(1990, 1, 1));
    }
}
