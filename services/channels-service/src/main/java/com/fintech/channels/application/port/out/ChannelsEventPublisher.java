package com.fintech.channels.application.port.out;

import com.fintech.channels.domain.DeviceContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ChannelsEventPublisher {
    void publishSessionStarted(UUID sessionId, UUID partyId, String channelType, DeviceContext device);
    void publishSessionExpired(UUID sessionId, String reason, List<UUID> pendingIntentIds);
    void publishIntentCaptured(UUID intentId, UUID sessionId, String intentType,
                               String productTypeHint, BigDecimal requestedAmount);
    void publishIntentRouted(UUID intentId, String routedTo);
    void publishIntentAbandoned(UUID intentId, String reason);
    void publishApplicationStarted(UUID intentId, UUID partyId, UUID channelId,
                                   String productTypeHint, BigDecimal requestedAmount, String promoterCode);
    void publishLeadCreated(UUID leadId, String channelType, UUID promoterPartyId);
    void publishLeadConverted(UUID leadId, UUID convertedPartyId);
}
