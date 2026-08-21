package com.fintech.notifications;

import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.NotificationTemplate;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTemplateTest {

    @Test
    void renderBody_substitutesKnownPlaceholders() {
        NotificationTemplate t = NotificationTemplate.create(EventType.OFFER_PRESENTED,
                NotificationChannel.WHATSAPP, "es-MX", null,
                "{{nombre}}, tu oferta de {{offeredAmount}} está lista");

        String rendered = t.renderBody(Map.of("nombre", "Ana", "offeredAmount", "$10,000.00"));

        assertThat(rendered).isEqualTo("Ana, tu oferta de $10,000.00 está lista");
    }

    @Test
    void renderBody_leavesPlaceholderWhenVariableMissing() {
        NotificationTemplate t = NotificationTemplate.create(EventType.WELCOME_ACTIVATED,
                NotificationChannel.PUSH_NOTIFICATION, "es-MX", null, "Hola {{nombre}}");

        String rendered = t.renderBody(Map.of());

        assertThat(rendered).isEqualTo("Hola {{nombre}}");
    }

    @Test
    void renderSubject_nullWhenTemplateHasNoSubject() {
        NotificationTemplate t = NotificationTemplate.create(EventType.PAYMENT_REMINDER,
                NotificationChannel.PUSH_NOTIFICATION, "es-MX", null, "Tu pago vence");

        assertThat(t.renderSubject(Map.of())).isNull();
    }

    @Test
    void renderSubject_substitutesPlaceholders() {
        NotificationTemplate t = NotificationTemplate.create(EventType.LOAN_SETTLED,
                NotificationChannel.EMAIL, "es-MX", "Tu {{productType}} está liquidado", "body");

        assertThat(t.renderSubject(Map.of("productType", "SME_LOAN")))
                .isEqualTo("Tu SME_LOAN está liquidado");
    }
}
