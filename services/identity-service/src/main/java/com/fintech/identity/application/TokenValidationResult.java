package com.fintech.identity.application;

import com.fintech.identity.domain.Channel;

import java.util.List;
import java.util.UUID;

/**
 * @param partyId {@code partyId} en canal MOBILE, {@code staffUserId} en canal BACKOFFICE
 */
public record TokenValidationResult(UUID partyId, List<String> roles, String deviceId, Channel channel) {}
