package com.fintech.notifications.application;

import com.fintech.notifications.domain.ChannelStrategy;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.ValueTier;

import java.util.List;

public record CreateNotificationPolicyCommand(
        EventType eventType,
        ValueTier valueTier,
        ChannelStrategy channelStrategy,
        NotificationChannel primaryChannel,
        List<NotificationChannel> fallbackChannels
) {}
