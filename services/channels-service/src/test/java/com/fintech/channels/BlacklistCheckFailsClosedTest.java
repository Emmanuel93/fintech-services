package com.fintech.channels;

import com.fintech.channels.application.port.out.*;
import com.fintech.channels.application.service.IntentService;
import com.fintech.channels.application.service.PartyStatusUnavailableException;
import com.fintech.channels.domain.CustomerIntent;
import com.fintech.channels.domain.DeviceContext;
import com.fintech.channels.domain.IntentType;
import com.fintech.channels.domain.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * IR-02 — la comprobación de lista negra falla CERRADO.
 *
 * <p>Esta rama existía y <b>ninguna prueba la cubría</b>, que es exactamente por qué sobrevivió con
 * la polaridad invertida: cualquier excepción al consultar a party se registraba con un `WARN` y el
 * intent seguía su camino <b>sin comprobar</b>.
 *
 * <p>Para un control regulatorio eso está al revés. Que la comprobación no esté disponible tiene que
 * detener, no dejar pasar: con el screening de listas como condición para colocar, fallar abierto
 * significa dispersar dinero a alguien sin haber comprobado que no está en una lista, porque un
 * servicio estaba caído — y sin que nada lo indique después.
 *
 * <p><b>Y no saber tampoco es rechazar.</b> Un party en lista negra abandona el intent con motivo,
 * porque se sabe. Un party que no se pudo comprobar rechaza el enrutamiento, que es reintentable:
 * abandonar afirmaría algo que nadie comprobó.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlacklistCheckFailsClosedTest {

    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID INTENT  = UUID.randomUUID();
    private static final UUID PARTY   = UUID.randomUUID();
    private static final String USER  = "ejecutivo-1";

    @Mock SessionRepository        sessions;
    @Mock ChannelRepository        channels;
    @Mock CustomerIntentRepository intents;
    @Mock ChannelsEventPublisher   events;
    @Mock PartyStatusChecker       partyStatus;

    IntentService service;

    @BeforeEach
    void setUp() {
        service = new IntentService(sessions, channels, intents, events, partyStatus);

        Session session = Session.start(UUID.randomUUID(), "MOBILE_APP", PARTY, DeviceContext.builder().build(), 30);
        given(sessions.findById(SESSION)).willReturn(Optional.of(session));

        CustomerIntent intent = CustomerIntent.capture(
                SESSION, IntentType.CREDIT_APPLICATION, "PERSONAL_LOAN", new BigDecimal("10000"), null);
        given(intents.findByIdAndSessionId(INTENT, SESSION)).willReturn(Optional.of(intent));
        given(intents.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("si party no contesta, el intent NO avanza")
    void anUnavailableCheckStopsTheIntent() {
        given(partyStatus.check(PARTY, USER))
                .willThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.routeIntent(SESSION, INTENT, USER))
                .isInstanceOf(PartyStatusUnavailableException.class)
                .hasMessageContaining("no avanza sin esa comprobación");

        // Y no se publica nada: ni enrutado ni abandonado. No se sabe, así que no se afirma nada.
        then(events).should(never()).publishIntentAbandoned(any(), any());
    }

    @Test
    @DisplayName("no saber NO es rechazar: no se abandona el intent")
    void notKnowingIsNotRejecting() {
        // Abandonar diría «esta persona está en lista negra», que es justo lo que no se comprobó.
        // El rechazo del enrutamiento es reintentable en cuanto party conteste; un abandono no.
        given(partyStatus.check(PARTY, USER)).willThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.routeIntent(SESSION, INTENT, USER))
                .isInstanceOf(PartyStatusUnavailableException.class);

        then(intents).should(never()).save(any());
    }

    @Test
    @DisplayName("un party en lista negra sí abandona el intent, con motivo")
    void aBlacklistedPartyAbandonsTheIntent() {
        given(partyStatus.check(PARTY, USER))
                .willReturn(new PartyStatusChecker.PartyStatus("BLACKLISTED", true));

        CustomerIntent result = service.routeIntent(SESSION, INTENT, USER);

        assertThat(result.getStatus()).isEqualTo("ABANDONED");
        then(events).should().publishIntentAbandoned(any(), org.mockito.ArgumentMatchers.eq("PARTY_BLACKLISTED"));
    }

    @Test
    @DisplayName("un party limpio pasa y el intent se enruta")
    void aCleanPartyRoutesNormally() {
        given(partyStatus.check(PARTY, USER))
                .willReturn(new PartyStatusChecker.PartyStatus("ACTIVE", true));

        CustomerIntent result = service.routeIntent(SESSION, INTENT, USER);

        assertThat(result.getStatus()).isNotEqualTo("ABANDONED");
    }
}
