package com.fintech.channels.infrastructure.adapter.in.api;

import com.fintech.channels.application.service.SessionService;
import com.fintech.channels.domain.DeviceContext;
import com.fintech.channels.domain.DeviceType;
import com.fintech.channels.infrastructure.adapter.in.api.dto.SessionResponse;
import com.fintech.channels.infrastructure.adapter.in.api.dto.StartSessionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "Sessions", description = "Business session lifecycle")
class SessionController {

    private final SessionService sessionService;

    SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @Operation(summary = "Start a new business session")
    ResponseEntity<SessionResponse> startSession(@Valid @RequestBody StartSessionRequest req,
                                                  HttpServletRequest httpRequest) {
        String userIdHeader = httpRequest.getHeader("X-User-Id");
        UUID partyId = userIdHeader != null ? UUID.fromString(userIdHeader) : null;

        DeviceContext device = buildDeviceContext(req, httpRequest);
        var session = sessionService.startSession(req.channelType(), partyId, device);
        return ResponseEntity.status(HttpStatus.CREATED).body(SessionResponse.from(session));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get session by ID")
    ResponseEntity<SessionResponse> getSession(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(SessionResponse.from(sessionService.getSession(sessionId)));
    }

    @PutMapping("/{sessionId}/close")
    @Operation(summary = "Close session and abandon pending intents")
    ResponseEntity<SessionResponse> closeSession(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(SessionResponse.from(sessionService.closeSession(sessionId)));
    }

    // ── Construcción del DeviceContext ────────────────────────────────────────

    private static DeviceContext buildDeviceContext(StartSessionRequest req,
                                                    HttpServletRequest httpRequest) {
        String userAgent  = httpRequest.getHeader("User-Agent");
        String ipAddress  = ClientIpExtractor.extract(httpRequest);
        // Gateway puede inyectar X-Country-Code desde GeoIP (cabecera opcional)
        String ipCountry  = httpRequest.getHeader("X-Country-Code");

        String deviceType = resolveDeviceType(req.deviceType(), userAgent);
        String[] browserInfo = parseBrowser(userAgent);

        return DeviceContext.builder()
                .deviceId(req.deviceId())
                .deviceType(deviceType)
                .deviceModel(req.deviceModel())
                .deviceManufacturer(req.deviceManufacturer())
                .os(req.os())
                .osVersion(req.osVersion())
                .appVersion(req.appVersion())
                .sdkVersion(req.sdkVersion())
                .networkType(req.networkType())
                .userAgent(userAgent)
                .browser(browserInfo[0])
                .browserVersion(browserInfo[1])
                .ipAddress(ipAddress)
                .ipCountry(ipCountry)
                .isTrustedDevice(req.isTrustedDevice())
                .isRooted(req.isRooted())
                .isEmulator(req.isEmulator())
                .build();
    }

    /**
     * Resuelve el DeviceType: prioriza el valor del cliente; si no viene,
     * infiere desde el User-Agent.
     */
    private static String resolveDeviceType(String clientDeviceType, String userAgent) {
        if (clientDeviceType != null && !clientDeviceType.isBlank()) {
            try {
                return DeviceType.valueOf(clientDeviceType.toUpperCase()).name();
            } catch (IllegalArgumentException ignored) {}
        }
        return inferDeviceTypeFromUserAgent(userAgent);
    }

    private static String inferDeviceTypeFromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return DeviceType.UNKNOWN.name();
        String ua = userAgent.toLowerCase();
        if (ua.contains("ipad") || ua.contains("tablet") || ua.contains("kindle")) return DeviceType.TABLET.name();
        if (ua.contains("mobile") || ua.contains("iphone") || ua.contains("android")) return DeviceType.MOBILE.name();
        if (ua.contains("mozilla") || ua.contains("chrome") || ua.contains("safari") || ua.contains("gecko")) return DeviceType.DESKTOP.name();
        return DeviceType.UNKNOWN.name();
    }

    /**
     * Parseo simplificado de browser/versión desde User-Agent.
     * Retorna {@code [browser, browserVersion]}.
     */
    private static String[] parseBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return new String[]{null, null};
        String ua = userAgent.toLowerCase();
        if (ua.contains("edg/")) return new String[]{"Edge", extractVersion(userAgent, "Edg/")};
        if (ua.contains("chrome/")) return new String[]{"Chrome", extractVersion(userAgent, "Chrome/")};
        if (ua.contains("firefox/")) return new String[]{"Firefox", extractVersion(userAgent, "Firefox/")};
        if (ua.contains("safari/") && !ua.contains("chrome")) return new String[]{"Safari", extractVersion(userAgent, "Version/")};
        if (ua.contains("opr/") || ua.contains("opera/")) return new String[]{"Opera", extractVersion(userAgent, "OPR/")};
        return new String[]{"Other", null};
    }

    private static String extractVersion(String userAgent, String token) {
        int idx = userAgent.indexOf(token);
        if (idx < 0) return null;
        String rest = userAgent.substring(idx + token.length());
        int end = rest.indexOf(' ');
        return end > 0 ? rest.substring(0, end) : rest;
    }
}
