package com.fintech.notifications;

import com.fintech.notifications.application.service.RecipientNotificationService;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.UnknownRecipientException;
import com.fintech.notifications.infrastructure.adapter.in.messaging.NotificationRequestedListener;
import com.fintech.notifications.infrastructure.adapter.in.messaging.NotificationRequestedPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * El consumidor genérico: lo que se prueba es que <b>no sepa nada</b>.
 *
 * <p>Pasa lo que recibe y no interpreta ni el tipo de destinatario ni la clave del evento. Si algún
 * día alguien mete aquí una rama por dominio, estos tests dejan de tener sentido — y esa es la
 * señal.
 */
@ExtendWith(MockitoExtension.class)
class NotificationRequestedListenerTest {

    @Mock RecipientNotificationService service;

    NotificationRequestedListener listener;

    @BeforeEach
    void setUp() {
        listener = new NotificationRequestedListener(service);
    }

    private static NotificationRequestedPayload payload(String type, String eventKey, String sourceEventId) {
        return new NotificationRequestedPayload(sourceEventId, type, UUID.randomUUID(), eventKey,
                Map.of("cantidad", "40"), List.of(NotificationChannel.IN_APP));
    }

    @Test
    @DisplayName("pasa el aviso tal cual, sin interpretar tipo ni clave")
    void forwardsVerbatim() {
        var p = payload("ASESOR_EXTERNO", "LO_QUE_SEA", "evt-1");

        listener.onMessage(p);

        verify(service).notify(eq("evt-1"), eq("ASESOR_EXTERNO"), eq(p.recipientId()),
                eq("LO_QUE_SEA"), eq(Map.of("cantidad", "40")),
                eq(List.of(NotificationChannel.IN_APP)));
    }

    @Test
    @DisplayName("sin sourceEventId genera uno estable — dos entregas del mismo hecho no duplican")
    void derivesAStableSourceEventId() {
        var p = payload("STAFF", "STAFF_CLIENTES_ASIGNADOS", null);

        listener.onMessage(p);
        listener.onMessage(p);

        ArgumentCaptor<String> ids = ArgumentCaptor.forClass(String.class);
        verify(service, times(2)).notify(ids.capture(), any(), any(), any(), any(), any());
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
        assertThat(ids.getAllValues().get(0)).contains("STAFF_CLIENTES_ASIGNADOS");
    }

    @Test
    @DisplayName("un destinatario no registrado se descarta, no rebota en el consumidor")
    void anUnknownRecipientIsDiscarded() {
        willThrow(new UnknownRecipientException("STAFF", UUID.randomUUID()))
                .given(service).notify(any(), any(), any(), any(), any(), any());

        // Si esto propagara, el mensaje volvería a entregarse para siempre y bloquearía la
        // partición para todos los avisos que sí tienen a quién llegarle.
        assertThatCode(() -> listener.onMessage(payload("STAFF", "X", "evt-2")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un payload incompleto se descarta sin llamar al servicio")
    void anIncompletePayloadIsDropped() {
        listener.onMessage(new NotificationRequestedPayload("e", null, UUID.randomUUID(), "K", Map.of(), List.of()));
        listener.onMessage(new NotificationRequestedPayload("e", "STAFF", null, "K", Map.of(), List.of()));
        listener.onMessage(new NotificationRequestedPayload("e", "STAFF", UUID.randomUUID(), " ", Map.of(), List.of()));
        listener.onMessage(null);

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("variables nulas llegan como mapa vacío, no como null")
    void nullVariablesBecomeAnEmptyMap() {
        listener.onMessage(new NotificationRequestedPayload("evt-3", "STAFF", UUID.randomUUID(),
                "K", null, null));

        verify(service).notify(any(), any(), any(), eq("K"), eq(Map.of()), isNull());
    }
}
