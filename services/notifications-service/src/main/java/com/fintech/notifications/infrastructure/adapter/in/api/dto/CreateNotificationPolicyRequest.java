package com.fintech.notifications.infrastructure.adapter.in.api.dto;

import com.fintech.notifications.domain.ChannelStrategy;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationChannel;
import com.fintech.notifications.domain.ValueTier;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateNotificationPolicyRequest(
        @NotNull EventType eventType,
        @NotNull ValueTier valueTier,
        @NotNull ChannelStrategy channelStrategy,
        @NotNull NotificationChannel primaryChannel,
        List<NotificationChannel> fallbackChannels
) {}
