package com.fintech.identity.application.service;

import com.fintech.identity.application.DeviceTrackingResult;
import com.fintech.identity.application.port.out.DeviceRepository;
import com.fintech.identity.domain.Device;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Registra o actualiza el dispositivo del cliente en cada autenticación.
 * Determina si el dispositivo es nuevo para el party (primer acceso)
 * o ya conocido, y retorna el UUID de registro para incluirlo en el evento Kafka.
 */
@Service
@Transactional
public class DeviceTracker {

    private final DeviceRepository deviceRepository;

    public DeviceTracker(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * Registra o actualiza el dispositivo.
     *
     * @param partyId      UUID del party autenticado
     * @param clientDeviceId deviceId enviado por el cliente (debe ser no-nulo)
     * @param userAgent    User-Agent del request
     * @param ipAddress    IP resuelta del cliente
     * @return resultado con UUID del registro y flag de dispositivo nuevo
     */
    public DeviceTrackingResult track(UUID partyId, String clientDeviceId,
                               String userAgent, String ipAddress) {
        return deviceRepository
                .findByPartyIdAndClientDeviceId(partyId, clientDeviceId)
                .map(existing -> {
                    existing.recordSeen(ipAddress, userAgent);
                    deviceRepository.save(existing);
                    return new DeviceTrackingResult(existing.getId(), false);
                })
                .orElseGet(() -> {
                    DeviceInfo info = parseUserAgent(userAgent);
                    Device device = Device.create(
                            partyId, clientDeviceId,
                            info.platform(), info.os(), info.browser(),
                            info.model(), userAgent, ipAddress);
                    Device saved = deviceRepository.save(device);
                    return new DeviceTrackingResult(saved.getId(), true);
                });
    }

    // ── User-Agent parsing (heurístico, sustituible por ua-parser-java) ──

    private DeviceInfo parseUserAgent(String ua) {
        if (ua == null || ua.isBlank()) {
            return new DeviceInfo("UNKNOWN", null, null, null);
        }
        String lower = ua.toLowerCase();

        String platform;
        String os = null;
        String browser = null;
        String model = null;

        if (lower.contains("iphone") || lower.contains("ipad")) {
            platform = "IOS";
            model = lower.contains("iphone") ? "iPhone" : "iPad";
            // iOS 17_4 → "iOS 17.4"
            int idx = lower.indexOf("cpu iphone os ");
            if (idx == -1) idx = lower.indexOf("cpu os ");
            if (idx != -1) {
                String osStr = ua.substring(idx).split(" ")[3].replace("_", ".");
                os = "iOS " + osStr;
            }
        } else if (lower.contains("android")) {
            platform = "ANDROID";
            int idx = lower.indexOf("android ");
            if (idx != -1) {
                os = "Android " + ua.substring(idx + 8).split("[;) ]")[0];
            }
        } else if (lower.contains("mozilla") || lower.contains("webkit")) {
            platform = "WEB";
            if (lower.contains("edg/"))       browser = "Edge";
            else if (lower.contains("opr/"))  browser = "Opera";
            else if (lower.contains("chrome"))browser = "Chrome";
            else if (lower.contains("firefox"))browser = "Firefox";
            else if (lower.contains("safari")) browser = "Safari";

            if (lower.contains("windows"))    os = "Windows";
            else if (lower.contains("mac os"))os = "macOS";
            else if (lower.contains("linux")) os = "Linux";
        } else {
            platform = "API";
        }

        return new DeviceInfo(platform, os, browser, model);
    }

    private record DeviceInfo(String platform, String os, String browser, String model) {}
}
