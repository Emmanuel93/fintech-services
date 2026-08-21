package com.fintech.channels.infrastructure.adapter.in.api.dto;

import com.fintech.channels.domain.DeviceContext;
import com.fintech.channels.domain.Session;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID sessionId,
        UUID channelId,
        String channelType,
        UUID partyId,
        String status,
        Instant startedAt,
        Instant expiresAt,
        Instant closedAt,
        DeviceContextResponse device
) {
    public static SessionResponse from(Session s) {
        return new SessionResponse(
                s.getSessionId(), s.getChannelId(), s.getChannelType(),
                s.getPartyId(), s.getStatus(),
                s.getStartedAt(), s.getExpiresAt(), s.getClosedAt(),
                DeviceContextResponse.from(s.getDevice()));
    }

    public record DeviceContextResponse(
            String deviceId,
            String deviceType,
            String deviceModel,
            String deviceManufacturer,
            String os,
            String osVersion,
            String appVersion,
            String sdkVersion,
            String networkType,
            String browser,
            String browserVersion,
            String ipAddress,
            String ipCountry,
            boolean isTrustedDevice,
            boolean isRooted,
            boolean isEmulator
    ) {
        static DeviceContextResponse from(DeviceContext d) {
            if (d == null) return null;
            return new DeviceContextResponse(
                    d.getDeviceId(), d.getDeviceType(), d.getDeviceModel(),
                    d.getDeviceManufacturer(), d.getOs(), d.getOsVersion(),
                    d.getAppVersion(), d.getSdkVersion(), d.getNetworkType(),
                    d.getBrowser(), d.getBrowserVersion(),
                    d.getIpAddress(), d.getIpCountry(),
                    d.isTrustedDevice(), d.isRooted(), d.isEmulator());
        }
    }
}
