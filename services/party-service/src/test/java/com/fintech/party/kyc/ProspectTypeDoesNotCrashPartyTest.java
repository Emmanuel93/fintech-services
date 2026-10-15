package com.fintech.party.kyc;

import com.fintech.party.application.port.out.KycVerificationRepository;
import com.fintech.party.application.port.out.PartyEventPublisher;
import com.fintech.party.application.port.out.PartyRepository;
import com.fintech.party.application.service.PartyService;
import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyType;
import com.fintech.party.infrastructure.adapter.in.messaging.ProspectCreatedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * La trampa que estaba armada: un tipo de prospecto que {@code PartyType} no tiene.
 *
 * <h2>Lo que pasaba</h2>
 *
 * Era {@code PartyType.valueOf(payload.prospectType())} sin red. Los dos catálogos no coinciden —
 * {@code ProspectType} tiene {@code DISTRIBUTOR}, {@code PartyType} sólo {@code INDIVIDUAL} y
 * {@code BUSINESS}— así que un prospecto con ese valor reventaba el listener con
 * {@code IllegalArgumentException}. Y este servicio corre <b>sin DLT</b>: el mensaje se reintentaba
 * en bucle, la persona nunca se creaba, y como no hay fila en {@code party} tampoco hay ficha, ni
 * veredicto de verificación — con lo que esa persona quedaba bloqueada en todas las compuertas sin
 * que nada explicara por qué.
 *
 * <p>No ocurría todavía <b>sólo</b> porque ningún productor emitía ese valor. El enum lo tiene, el
 * {@code CHECK} de la base lo admite, y el endpoint que lo acepta es {@code permitAll()}.
 *
 * <h2>Y por qué INDIVIDUAL es la traducción correcta</h2>
 *
 * Porque un distribuidor <b>es una persona física con actividad empresarial</b>, y porque el
 * invariante I-03 dice que ser distribuidor es <b>party + rol</b>, no un tipo de persona distinto.
 */
@ExtendWith(MockitoExtension.class)
class ProspectTypeDoesNotCrashPartyTest {

    @Mock PartyRepository partyRepository;
    @Mock KycVerificationRepository kycRepository;
    @Mock PartyEventPublisher eventPublisher;

    PartyService service;

    @BeforeEach
    void setUp() {
        service = new PartyService(partyRepository, kycRepository, eventPublisher);
    }

    private ProspectCreatedPayload payloadWithType(String prospectType) {
        return new ProspectCreatedPayload(
                UUID.randomUUID(), prospectType, "Juan", "García", "López",
                "GARJ900101HDFXXX01", "GARJ900101XXX",
                LocalDate.of(1990, 1, 1), UUID.randomUUID().toString());
    }

    private Party createWithType(String prospectType) {
        given(partyRepository.existsByProspectId(any())).willReturn(false);
        given(partyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        return service.createFromProspect(payloadWithType(prospectType));
    }

    // ── El caso que reventaba ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DISTRIBUTOR no tumba el listener: crea la persona como física")
    void adistributorDoesNotCrashTheListener() {
        Party created = createWithType("DISTRIBUTOR");

        assertThat(created).isNotNull();
        assertThat(created.getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
    }

    @Test
    @DisplayName("un distribuidor NO es BUSINESS: es persona física con actividad empresarial")
    void adistributorIsNotAbusiness() {
        // Mandarlo a BUSINESS metería a una persona física en el carril de las morales — un error
        // más difícil de ver que un crash, porque no falla: produce datos equivocados en silencio.
        assertThat(createWithType("DISTRIBUTOR").getPartyType()).isNotEqualTo(PartyType.BUSINESS);
    }

    // ── Y cualquier otro valor que alguien añada mañana ──────────────────────────────────────

    @Test
    @DisplayName("un tipo desconocido tampoco tumba nada")
    void anunknownTypeDoesNotCrashEither() {
        assertThatCode(() -> createWithType("FRANQUICIATARIO")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("sin tipo se crea igual: no crear la persona es peor que crearla conservadora")
    void anabsentTypeStillCreatesTheParty() {
        // El alta ya ocurrió del otro lado y el prospecto existe. Dejar a party sin su fila deja a
        // la persona sin ficha, sin veredicto y bloqueada en todo, sin que nadie sepa por qué.
        assertThat(createWithType(null).getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
        assertThat(createWithType("   ").getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
    }

    // ── Lo que no cambió ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("los tipos que SÍ existen se siguen respetando")
    void theknownTypesStillMapThrough() {
        assertThat(createWithType("INDIVIDUAL").getPartyType()).isEqualTo(PartyType.INDIVIDUAL);
        assertThat(createWithType("BUSINESS").getPartyType()).isEqualTo(PartyType.BUSINESS);
    }
}
