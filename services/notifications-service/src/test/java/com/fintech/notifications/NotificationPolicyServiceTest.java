package com.fintech.notifications;

import com.fintech.notifications.application.CreateNotificationPolicyCommand;
import com.fintech.notifications.application.port.out.NotificationPolicyRepository;
import com.fintech.notifications.application.service.NotificationPolicyService;
import com.fintech.notifications.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class NotificationPolicyServiceTest {

    @Mock NotificationPolicyRepository repository;
    NotificationPolicyService service;

    @BeforeEach
    void setUp() {
        service = new NotificationPolicyService(repository);
    }

    @Test
    void create_firstVersion_isActive() {
        given(repository.findActiveByEventType(EventType.PAYMENT_REMINDER)).willReturn(Optional.empty());
        given(repository.maxVersionFor(EventType.PAYMENT_REMINDER)).willReturn(0);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        NotificationPolicy created = service.create(new CreateNotificationPolicyCommand(
                EventType.PAYMENT_REMINDER, ValueTier.ALTO, ChannelStrategy.SEQUENTIAL_FALLBACK,
                NotificationChannel.PUSH_NOTIFICATION, List.of(NotificationChannel.WHATSAPP)));

        assertThat(created.getVersion()).isEqualTo(1);
        assertThat(created.isActive()).isTrue();
    }

    @Test
    void create_deprecatesPriorActive_andBumpsVersion() {
        NotificationPolicy prior = NotificationPolicy.create(EventType.PAYMENT_REMINDER, ValueTier.ALTO,
                ChannelStrategy.SEQUENTIAL_FALLBACK, NotificationChannel.PUSH_NOTIFICATION, List.of(), 1);
        given(repository.findActiveByEventType(EventType.PAYMENT_REMINDER)).willReturn(Optional.of(prior));
        given(repository.maxVersionFor(EventType.PAYMENT_REMINDER)).willReturn(1);
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        NotificationPolicy created = service.create(new CreateNotificationPolicyCommand(
                EventType.PAYMENT_REMINDER, ValueTier.ALTO, ChannelStrategy.SIMULTANEOUS,
                NotificationChannel.WHATSAPP, List.of()));

        assertThat(prior.isActive()).isFalse();
        assertThat(created.getVersion()).isEqualTo(2);

        ArgumentCaptor<NotificationPolicy> captor = ArgumentCaptor.forClass(NotificationPolicy.class);
        then(repository).should(org.mockito.Mockito.times(2)).save(captor.capture());
    }

    @Test
    void listActive_delegatesToRepository() {
        given(repository.findAllActive()).willReturn(List.of());

        assertThat(service.listActive()).isEmpty();
    }
}
