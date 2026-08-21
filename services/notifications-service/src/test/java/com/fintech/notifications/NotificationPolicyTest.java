package com.fintech.notifications;

import com.fintech.notifications.domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPolicyTest {

    @Test
    void orderedChannels_primaryFirstThenFallbacksInOrder() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.OFFER_PRESENTED, ValueTier.ALTO,
                ChannelStrategy.SIMULTANEOUS, NotificationChannel.PUSH_NOTIFICATION,
                List.of(NotificationChannel.WHATSAPP, NotificationChannel.EMAIL), 1);

        assertThat(policy.orderedChannels())
                .containsExactly(NotificationChannel.PUSH_NOTIFICATION, NotificationChannel.WHATSAPP, NotificationChannel.EMAIL);
    }

    @Test
    void create_startsActive() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.LOAN_SETTLED, ValueTier.ALTO,
                ChannelStrategy.SIMULTANEOUS, NotificationChannel.PUSH_NOTIFICATION, List.of(), 1);

        assertThat(policy.isActive()).isTrue();
    }

    @Test
    void deprecate_flipsStatus() {
        NotificationPolicy policy = NotificationPolicy.create(EventType.LOAN_SETTLED, ValueTier.ALTO,
                ChannelStrategy.SIMULTANEOUS, NotificationChannel.PUSH_NOTIFICATION, List.of(), 1);

        policy.deprecate();

        assertThat(policy.isActive()).isFalse();
        assertThat(policy.getStatus()).isEqualTo(PolicyStatus.DEPRECATED);
    }
}
