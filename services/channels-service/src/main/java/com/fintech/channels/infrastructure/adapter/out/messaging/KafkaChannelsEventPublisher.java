package com.fintech.channels.infrastructure.adapter.out.messaging;

import com.fintech.channels.application.port.out.ChannelsEventPublisher;
import com.fintech.channels.domain.DeviceContext;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class KafkaChannelsEventPublisher implements ChannelsEventPublisher {

    private static final String SESSION_STARTED      = "channels.session-started";
    private static final String SESSION_EXPIRED      = "channels.session-expired";
    private static final String INTENT_CAPTURED      = "channels.intent-captured";
    private static final String INTENT_ROUTED        = "channels.intent-routed";
    private static final String INTENT_ABANDONED     = "channels.intent-abandoned";
    private static final String APPLICATION_STARTED  = "channels.application-started";
    private static final String LEAD_CREATED         = "channels.lead-created";
    private static final String LEAD_CONVERTED       = "channels.lead-converted";

    private final KafkaTemplate<String, Object> kafka;

    KafkaChannelsEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publishSessionStarted(UUID sessionId, UUID partyId, String channelType,
                                      DeviceContext device) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId",   sessionId);
        payload.put("partyId",     partyId != null ? partyId : "");
        payload.put("channelType", channelType);
        payload.put("occurredAt",  Instant.now());

        if (device != null) {
            Map<String, Object> deviceMap = new HashMap<>();
            deviceMap.put("deviceId",           nullSafe(device.getDeviceId()));
            deviceMap.put("deviceType",          nullSafe(device.getDeviceType()));
            deviceMap.put("deviceModel",         nullSafe(device.getDeviceModel()));
            deviceMap.put("deviceManufacturer",  nullSafe(device.getDeviceManufacturer()));
            deviceMap.put("os",                  nullSafe(device.getOs()));
            deviceMap.put("osVersion",           nullSafe(device.getOsVersion()));
            deviceMap.put("appVersion",          nullSafe(device.getAppVersion()));
            deviceMap.put("sdkVersion",          nullSafe(device.getSdkVersion()));
            deviceMap.put("networkType",         nullSafe(device.getNetworkType()));
            deviceMap.put("browser",             nullSafe(device.getBrowser()));
            deviceMap.put("browserVersion",      nullSafe(device.getBrowserVersion()));
            deviceMap.put("ipAddress",           nullSafe(device.getIpAddress()));
            deviceMap.put("ipCountry",           nullSafe(device.getIpCountry()));
            deviceMap.put("isTrustedDevice",     device.isTrustedDevice());
            deviceMap.put("isRooted",            device.isRooted());
            deviceMap.put("isEmulator",          device.isEmulator());
            payload.put("device", deviceMap);
        }

        kafka.send(SESSION_STARTED, sessionId.toString(), payload);
    }

    @Override
    public void publishSessionExpired(UUID sessionId, String reason, List<UUID> pendingIntentIds) {
        kafka.send(SESSION_EXPIRED, sessionId.toString(), Map.of(
                "sessionId",      sessionId,
                "reason",         reason,
                "pendingIntents", pendingIntentIds,
                "occurredAt",     Instant.now()
        ));
    }

    @Override
    public void publishIntentCaptured(UUID intentId, UUID sessionId, String intentType,
                                      String productTypeHint, BigDecimal requestedAmount) {
        kafka.send(INTENT_CAPTURED, intentId.toString(), Map.of(
                "intentId",        intentId,
                "sessionId",       sessionId,
                "intentType",      intentType,
                "productTypeHint", nullSafe(productTypeHint),
                "requestedAmount", requestedAmount != null ? requestedAmount : "",
                "occurredAt",      Instant.now()
        ));
    }

    @Override
    public void publishIntentRouted(UUID intentId, String routedTo) {
        kafka.send(INTENT_ROUTED, intentId.toString(), Map.of(
                "intentId",   intentId,
                "routedTo",   routedTo,
                "occurredAt", Instant.now()
        ));
    }

    @Override
    public void publishIntentAbandoned(UUID intentId, String reason) {
        kafka.send(INTENT_ABANDONED, intentId.toString(), Map.of(
                "intentId",   intentId,
                "reason",     reason,
                "occurredAt", Instant.now()
        ));
    }

    @Override
    public void publishApplicationStarted(UUID intentId, UUID partyId, UUID channelId,
                                          String productTypeHint, BigDecimal requestedAmount, String promoterCode) {
        kafka.send(APPLICATION_STARTED, intentId.toString(), Map.of(
                "intentId",        intentId,
                "partyId",         partyId,
                "channelId",       channelId,
                "productTypeHint", nullSafe(productTypeHint),
                "requestedAmount", requestedAmount != null ? requestedAmount : "",
                "promoterCode",    nullSafe(promoterCode),
                "occurredAt",      Instant.now()
        ));
    }

    @Override
    public void publishLeadCreated(UUID leadId, String channelType, UUID promoterPartyId) {
        kafka.send(LEAD_CREATED, leadId.toString(), Map.of(
                "leadId",          leadId,
                "channelType",     channelType,
                "promoterPartyId", promoterPartyId != null ? promoterPartyId : "",
                "occurredAt",      Instant.now()
        ));
    }

    @Override
    public void publishLeadConverted(UUID leadId, UUID convertedPartyId) {
        kafka.send(LEAD_CONVERTED, leadId.toString(), Map.of(
                "leadId",           leadId,
                "convertedPartyId", convertedPartyId,
                "occurredAt",       Instant.now()
        ));
    }

    private static String nullSafe(String s) { return s != null ? s : ""; }
}
