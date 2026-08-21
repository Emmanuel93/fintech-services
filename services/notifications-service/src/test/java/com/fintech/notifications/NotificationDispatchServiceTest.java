package com.fintech.notifications;

import com.fintech.notifications.application.port.out.*;
import com.fintech.notifications.application.service.ContactInfo;
import com.fintech.notifications.application.service.NotificationDispatchService;
import com.fintech.notifications.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock NotificationPolicyRepository policyRepository;
    @Mock NotificationTemplateRepository templateRepository;
    @Mock NotificationRecordRepository recordRepository;
    @Mock PushNotificationAdapter pushAdapter;
    @Mock EmailAdapter emailAdapter;
    @Mock WhatsAppAdapter whatsAppAdapter;
    @Mock NotificationEventPublisher eventPublisher;

    NotificationDispatchService service;
    final UUID recipientId = UUID.randomUUID();
    final ContactInfo fullContact = new ContactInfo("Ana", "5511112222", "ana@example.com");

    @BeforeEach
    void setUp() {
        service = new NotificationDispatchService(policyRepository, templateRepository, recordRepository,
                pushAdapter, emailAdapter, whatsAppAdapter, eventPublisher);
    }

    private NotificationTemplate anyTemplate(NotificationChannel channel) {
        return NotificationTemplate.create(EventType.OFFER_PRESENTED, channel, "es-MX", null, "body");
    }

    @Test
    void noActivePolicy_isNoOp() {
        given(policyRepository.findActiveByEventType(EventType.OFFER_PRESENTED)).willReturn(Optional.empty());

        service.dispatch("evt-1", EventType.OFFER_PRESENTED, recipientId, fullContact, null, Map.of());

        then(recordRepository).shouldHaveNoInteractions();
        then(pushAdapter).shouldHaveNoInteractions();
    }

    @Test
    void simultaneousStrategy_sendsToAllAvailableChannels() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.OFFER_PRESENTED, ValueTier.ALTO,
                ChannelStrategy.SIMULTANEOUS, NotificationChannel.PUSH_NOTIFICATION,
                List.of(NotificationChannel.WHATSAPP, NotificationChannel.EMAIL), 1);
        given(policyRepository.findActiveByEventType(EventType.OFFER_PRESENTED)).willReturn(Optional.of(policy));
        given(templateRepository.findByEventTypeAndChannelAndLocale(any(), any(), any()))
                .willAnswer(inv -> Optional.of(anyTemplate(inv.getArgument(1))));
        given(pushAdapter.send(any(), any(), any())).willReturn(true);
        given(whatsAppAdapter.send(any(), any())).willReturn(true);
        given(emailAdapter.send(any(), any(), any())).willReturn(true);

        NotificationPreference preference = NotificationPreference.create(recipientId, "push-token-1", null);
        service.dispatch("evt-1", EventType.OFFER_PRESENTED, recipientId, fullContact, preference, Map.of());

        then(pushAdapter).should().send(eq("push-token-1"), any(), any());
        then(whatsAppAdapter).should().send(eq("5511112222"), any());
        then(emailAdapter).should().send(eq("ana@example.com"), any(), any());
        then(recordRepository).should(times(3)).save(any());
        then(eventPublisher).should(times(3)).publishNotificationSent(any());
    }

    @Test
    void sequentialFallback_skipsUnavailableChannel_attemptsOnlyFirstAvailable() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.PAYMENT_REMINDER, ValueTier.ALTO,
                ChannelStrategy.SEQUENTIAL_FALLBACK, NotificationChannel.PUSH_NOTIFICATION,
                List.of(NotificationChannel.WHATSAPP), 1);
        given(policyRepository.findActiveByEventType(EventType.PAYMENT_REMINDER)).willReturn(Optional.of(policy));
        given(templateRepository.findByEventTypeAndChannelAndLocale(any(), eq(NotificationChannel.WHATSAPP), any()))
                .willReturn(Optional.of(anyTemplate(NotificationChannel.WHATSAPP)));
        given(whatsAppAdapter.send(any(), any())).willReturn(true);

        // Sin preference (sin pushToken) — PUSH no está disponible, cae directo a WHATSAPP.
        service.dispatch("evt-2", EventType.PAYMENT_REMINDER, recipientId, fullContact, null, Map.of());

        then(pushAdapter).shouldHaveNoInteractions();
        then(whatsAppAdapter).should().send(eq("5511112222"), any());
        then(recordRepository).should(times(1)).save(any());
    }

    @Test
    void noContactInfoAnywhere_recordsFailedWithNoContactInfo() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.WELCOME_ACTIVATED, ValueTier.ALTO,
                ChannelStrategy.SIMULTANEOUS, NotificationChannel.PUSH_NOTIFICATION, List.of(), 1);
        given(policyRepository.findActiveByEventType(EventType.WELCOME_ACTIVATED)).willReturn(Optional.of(policy));

        service.dispatch("evt-3", EventType.WELCOME_ACTIVATED, recipientId, ContactInfo.empty(), null, Map.of());

        ArgumentCaptor<NotificationRecord> captor = ArgumentCaptor.forClass(NotificationRecord.class);
        then(recordRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(captor.getValue().getFailureReason()).isEqualTo("NO_CONTACT_INFO");
        then(pushAdapter).shouldHaveNoInteractions();
    }

    @Test
    void idempotent_existingRecordForSourceAndChannel_doesNotResend() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.DISBURSEMENT_COMPLETED, ValueTier.ALTO,
                ChannelStrategy.SEQUENTIAL_FALLBACK, NotificationChannel.PUSH_NOTIFICATION, List.of(), 1);
        given(policyRepository.findActiveByEventType(EventType.DISBURSEMENT_COMPLETED)).willReturn(Optional.of(policy));
        given(recordRepository.existsBySourceEventIdAndChannel("evt-4", NotificationChannel.PUSH_NOTIFICATION))
                .willReturn(true);
        NotificationPreference preference = NotificationPreference.create(recipientId, "push-token-1", null);

        service.dispatch("evt-4", EventType.DISBURSEMENT_COMPLETED, recipientId, fullContact, preference, Map.of());

        then(pushAdapter).shouldHaveNoInteractions();
        then(recordRepository).should(never()).save(any());
    }

    @Test
    void adapterFailure_sequentialFallback_triesNextChannel() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.INSTALLMENT_PAID, ValueTier.MEDIO,
                ChannelStrategy.SEQUENTIAL_FALLBACK, NotificationChannel.PUSH_NOTIFICATION,
                List.of(NotificationChannel.WHATSAPP), 1);
        given(policyRepository.findActiveByEventType(EventType.INSTALLMENT_PAID)).willReturn(Optional.of(policy));
        given(templateRepository.findByEventTypeAndChannelAndLocale(any(), any(), any()))
                .willAnswer(inv -> Optional.of(anyTemplate(inv.getArgument(1))));
        given(pushAdapter.send(any(), any(), any())).willReturn(false); // proveedor falla
        given(whatsAppAdapter.send(any(), any())).willReturn(true);

        NotificationPreference preference = NotificationPreference.create(recipientId, "push-token-1", null);
        service.dispatch("evt-5", EventType.INSTALLMENT_PAID, recipientId, fullContact, preference, Map.of());

        then(pushAdapter).should().send(any(), any(), any());
        then(whatsAppAdapter).should().send(any(), any());
        then(recordRepository).should(times(2)).save(any());
    }
}
