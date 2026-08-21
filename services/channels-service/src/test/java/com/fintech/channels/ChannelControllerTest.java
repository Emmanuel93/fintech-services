package com.fintech.channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.channels.application.service.ChannelService;
import com.fintech.channels.application.service.SessionService;
import com.fintech.channels.domain.*;
import com.fintech.channels.infrastructure.adapter.in.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest
@Import({JwtAuthenticationFilter.class, ChannelsExceptionHandler.class,
         com.fintech.channels.infrastructure.config.SecurityConfig.class,
         com.fintech.channels.infrastructure.config.ChannelsModuleConfig.class})
class ChannelControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean ChannelService channelService;
    @MockitoBean SessionService sessionService;
    @MockitoBean com.fintech.channels.application.service.IntentService intentService;
    @MockitoBean com.fintech.channels.application.service.LeadService leadService;
    @MockitoBean com.fintech.channels.application.ChannelsProperties channelsProperties;

    // ── Channels ──────────────────────────────────────────────────────────────

    @Test
    void listChannels_withoutAuth_returns401() throws Exception {
        mvc.perform(get("/api/v1/channels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listChannels_withCustomerRole_returns403() throws Exception {
        mvc.perform(get("/api/v1/channels")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void listChannels_withAdminRole_returns200() throws Exception {
        Channel channel = Channel.create(ChannelType.MOBILE_APP,
                Set.of(IntentType.CREDIT_APPLICATION), 30, 10, 100);
        given(channelService.listActiveChannels()).willReturn(List.of(channel));

        mvc.perform(get("/api/v1/channels")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channelType").value("MOBILE_APP"));
    }

    // ── Sessions ──────────────────────────────────────────────────────────────

    @Test
    void startSession_withValidRequest_returns201() throws Exception {
        UUID partyId = UUID.randomUUID();
        DeviceContext device = DeviceContext.builder()
                .deviceId("d1").deviceType("MOBILE").os("iOS").osVersion("17.2")
                .appVersion("2.0.0").networkType("WIFI")
                .ipAddress("192.168.0.1").isTrustedDevice(true)
                .isRooted(false).isEmulator(false).build();
        Session session = Session.start(UUID.randomUUID(), "MOBILE_APP", partyId, device, 30);
        given(sessionService.startSession(any(), any(), any())).willReturn(session);

        var body = Map.ofEntries(
                Map.entry("channelType",     "MOBILE_APP"),
                Map.entry("deviceId",        "d1"),
                Map.entry("deviceType",      "MOBILE"),
                Map.entry("deviceModel",     "iPhone 15 Pro"),
                Map.entry("os",              "iOS"),
                Map.entry("osVersion",       "17.2"),
                Map.entry("appVersion",      "2.0.0"),
                Map.entry("networkType",     "WIFI"),
                Map.entry("isTrustedDevice", true),
                Map.entry("isRooted",        false),
                Map.entry("isEmulator",      false)
        );

        mvc.perform(post("/api/v1/sessions")
                        .header("X-User-Id", partyId.toString())
                        .header("X-Roles", "CUSTOMER")
                        .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2)")
                        .header("X-Forwarded-For", "203.0.113.42, 10.0.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value(session.getSessionId().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.device.deviceType").value("MOBILE"));
    }

    @Test
    void startSession_withoutAuth_returns401() throws Exception {
        var body = Map.of("channelType", "MOBILE_APP", "isTrustedDevice", false,
                "isRooted", false, "isEmulator", false);
        mvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startSession_missingChannelType_returns400() throws Exception {
        var body = Map.of("isTrustedDevice", false, "isRooted", false, "isEmulator", false);
        mvc.perform(post("/api/v1/sessions")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSession_whenNotFound_returns404() throws Exception {
        UUID sessionId = UUID.randomUUID();
        given(sessionService.getSession(sessionId))
                .willThrow(new SessionNotFoundException(sessionId.toString()));

        mvc.perform(get("/api/v1/sessions/{id}", sessionId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString(sessionId.toString())));
    }

    // ── Intents ───────────────────────────────────────────────────────────────

    @Test
    void captureIntent_channelNotAllowed_returns400() throws Exception {
        UUID sessionId = UUID.randomUUID();
        given(intentService.captureIntent(any(), any(), any(), any(), any()))
                .willThrow(new IllegalArgumentException("IntentType not allowed (IR-03)"));

        var body = Map.of("intentType", "REFINANCING");
        mvc.perform(post("/api/v1/sessions/{id}/intents", sessionId)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Roles", "CUSTOMER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // ── IP extraction unit-level ──────────────────────────────────────────────

    @Test
    void extractClientIp_prefersXForwardedFor() throws Exception {
        UUID partyId = UUID.randomUUID();
        DeviceContext device = DeviceContext.builder()
                .deviceId("d").deviceType("DESKTOP").os("Windows").osVersion("11")
                .appVersion("1.0").networkType("ETHERNET")
                .ipAddress("203.0.113.5").isTrustedDevice(false)
                .isRooted(false).isEmulator(false).build();
        Session session = Session.start(UUID.randomUUID(), "WEB", partyId, device, 30);
        given(sessionService.startSession(any(), any(), any())).willReturn(session);

        var body = Map.of("channelType", "WEB", "isTrustedDevice", false,
                "isRooted", false, "isEmulator", false);

        // Sends X-Forwarded-For with client IP first in chain
        mvc.perform(post("/api/v1/sessions")
                        .header("X-User-Id", partyId.toString())
                        .header("X-Roles", "CUSTOMER")
                        .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1, 172.16.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        // IP extraction verified via SessionController static method in SessionControllerIpTest
    }
}
