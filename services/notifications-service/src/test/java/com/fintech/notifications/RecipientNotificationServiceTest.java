package com.fintech.notifications;

import com.fintech.notifications.application.port.out.*;
import com.fintech.notifications.application.service.RecipientNotificationService;
import com.fintech.notifications.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * El notificador que no sabe a quién le habla.
 *
 * <p>Lo que estas pruebas fijan no es un flujo sino una <b>independencia</b>: el servicio se
 * comporta igual con un destinatario de tipo {@code PARTY} que con uno de tipo
 * {@code ASESOR_EXTERNO} que nadie ha programado. Si algún día alguien mete un {@code if} por
 * tipo, el test de abajo lo caza.
 */
@ExtendWith(MockitoExtension.class)
class RecipientNotificationServiceTest {

    @Mock NotificationRecipientRepository recipients;
    @Mock NotificationPolicyRepository policies;
    @Mock NotificationTemplateRepository templates;
    @Mock NotificationRecordRepository records;
    @Mock PushNotificationAdapter push;
    @Mock EmailAdapter email;
    @Mock WhatsAppAdapter whatsApp;
    @Mock NotificationEventPublisher events;

    RecipientNotificationService service;

    @BeforeEach
    void setUp() {
        service = new RecipientNotificationService(recipients, policies, templates, records,
                push, email, whatsApp, events);
    }

    private static NotificationRecipient recipient(String type) {
        return NotificationRecipient.of(type, UUID.randomUUID(), "Quien sea",
                "5541829037", "a@b.mx", "push-token", "es-MX");
    }

    // ── La independencia del tipo ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un tipo que nadie programó se notifica igual que uno conocido")
    void anUnknownRecipientTypeBehavesLikeAnyOther() {
        for (String tipo : List.of("PARTY", "STAFF", "ASESOR_EXTERNO", "LO_QUE_SEA")) {
            NotificationRecipient r = recipient(tipo);
            given(recipients.find(eq(tipo), any())).willReturn(Optional.of(r));
            given(records.existsBySourceEventIdAndChannel(anyString(), any())).willReturn(false);

            var written = service.notify("evt-" + tipo, tipo, r.getRecipientId(), "LO_QUE_SEA",
                    Map.of(), List.of(NotificationChannel.IN_APP));

            assertThat(written).hasSize(1);
            assertThat(written.get(0).getRecipientType()).isEqualTo(tipo);
            assertThat(written.get(0).getStatus()).isEqualTo(NotificationStatus.SENT);
        }
        // Ninguna consulta de política: los canales los impuso el emisor.
        verifyNoInteractions(policies);
    }

    @Test
    @DisplayName("una clave de evento que no está en el enum se acepta y se graba tal cual")
    void anEventKeyOutsideTheEnumIsAccepted() {
        NotificationRecipient r = recipient("ASESOR_EXTERNO");
        given(recipients.find(any(), any())).willReturn(Optional.of(r));
        given(records.existsBySourceEventIdAndChannel(anyString(), any())).willReturn(false);

        var written = service.notify("evt-1", "ASESOR_EXTERNO", r.getRecipientId(),
                "CONVENIO_ESPERANDO_TU_FIRMA", Map.of(), List.of(NotificationChannel.IN_APP));

        assertThat(written.get(0).getEventKey()).isEqualTo("CONVENIO_ESPERANDO_TU_FIRMA");
        // Sin enum equivalente, la columna del catálogo interno queda nula — no se inventa uno.
        assertThat(written.get(0).getEventType()).isNull();
    }

    // ── El emisor manda ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("los canales del emisor ganan sobre la política")
    void senderChannelsWinOverPolicy() {
        NotificationRecipient r = recipient("STAFF");
        given(recipients.find(any(), any())).willReturn(Optional.of(r));
        given(records.existsBySourceEventIdAndChannel(anyString(), any())).willReturn(false);

        service.notify("evt-2", "STAFF", r.getRecipientId(), "CUALQUIERA", Map.of(),
                List.of(NotificationChannel.IN_APP));

        // Ni se consultó la política: el emisor ya dijo a dónde.
        verifyNoInteractions(policies);
        verifyNoInteractions(whatsApp, email, push);
    }

    @Test
    @DisplayName("sin política y sin canales impuestos no se manda nada — no se inventa un canal")
    void withoutPolicyOrOverrideNothingIsSent() {
        NotificationRecipient r = recipient("STAFF");
        given(recipients.find(any(), any())).willReturn(Optional.of(r));
        given(policies.findActiveByEventKey("SIN_POLITICA")).willReturn(Optional.empty());

        var written = service.notify("evt-3", "STAFF", r.getRecipientId(), "SIN_POLITICA",
                Map.of(), List.of());

        assertThat(written).isEmpty();
        verifyNoInteractions(records);
    }

    @Test
    @DisplayName("notificar a una entidad no registrada es un error del emisor, no un envío perdido")
    void notifyingAnUnregisteredRecipientFails() {
        given(recipients.find(any(), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.notify("evt-4", "STAFF", UUID.randomUUID(),
                "LO_QUE_SEA", Map.of(), List.of(NotificationChannel.IN_APP)))
                .isInstanceOf(UnknownRecipientException.class);
    }

    // ── Alcanzabilidad e idempotencia ────────────────────────────────────────────────────────

    @Test
    @DisplayName("si ningún canal alcanza al destinatario se graba NO_CONTACT_INFO")
    void unreachableRecipientIsRecorded() {
        NotificationRecipient soloCorreo = NotificationRecipient.of("STAFF", UUID.randomUUID(),
                "Sin push", null, "a@b.mx", null, "es-MX");
        given(recipients.find(any(), any())).willReturn(Optional.of(soloCorreo));

        var written = service.notify("evt-5", "STAFF", soloCorreo.getRecipientId(), "X",
                Map.of(), List.of(NotificationChannel.PUSH_NOTIFICATION));

        assertThat(written).hasSize(1);
        assertThat(written.get(0).getFailureReason()).isEqualTo("NO_CONTACT_INFO");
        verify(events).publishNotificationFailed(any());
    }

    @Test
    @DisplayName("un reenvío del mismo hecho no molesta dos veces")
    void replayDoesNotNotifyTwice() {
        NotificationRecipient r = recipient("STAFF");
        given(recipients.find(any(), any())).willReturn(Optional.of(r));
        given(records.existsBySourceEventIdAndChannel("evt-6", NotificationChannel.IN_APP))
                .willReturn(true);

        var written = service.notify("evt-6", "STAFF", r.getRecipientId(), "X", Map.of(),
                List.of(NotificationChannel.IN_APP));

        assertThat(written).isEmpty();
        verify(records, never()).save(any());
    }

    // ── Registro ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("registrar dos veces actualiza, no duplica")
    void registeringTwiceUpdates() {
        NotificationRecipient existing = recipient("STAFF");
        given(recipients.find(any(), any())).willReturn(Optional.of(existing));
        given(recipients.save(any())).willAnswer(i -> i.getArgument(0));

        service.register("STAFF", existing.getRecipientId(), null, null, "nuevo@kredius.mx", null, null);

        assertThat(existing.getEmail()).isEqualTo("nuevo@kredius.mx");
        // Un nulo no borra: el teléfono que ya se conocía sigue ahí.
        assertThat(existing.getPhone()).isEqualTo("5541829037");
        verify(recipients).save(existing);
    }

    @Test
    @DisplayName("un destinatario sin ninguna vía de contacto se rechaza al registrarlo")
    void anUnreachableRecipientIsRejectedAtRegistration() {
        assertThatThrownBy(() -> NotificationRecipient.of("STAFF", UUID.randomUUID(),
                "Nadie", null, null, null, "es-MX"))
                .isInstanceOf(InvalidRecipientException.class);
    }
}
