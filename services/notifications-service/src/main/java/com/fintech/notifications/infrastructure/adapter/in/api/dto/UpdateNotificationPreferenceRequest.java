package com.fintech.notifications.infrastructure.adapter.in.api.dto;

public record UpdateNotificationPreferenceRequest(String pushToken, String whatsappNumber) {}
