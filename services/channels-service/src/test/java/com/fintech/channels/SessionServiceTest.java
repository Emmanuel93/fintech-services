package com.fintech.channels;

import com.fintech.channels.application.ChannelsProperties;
import com.fintech.channels.application.port.out.*;
import com.fintech.channels.application.service.SessionService;
import com.fintech.channels.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock ChannelRepository channelRepository;
    @Mock SessionRepository sessionRepository;
    @Mock CustomerIntentRepository intentRepository;
    @Mock ChannelsEventPublisher eventPublisher;
    @Mock Channel mockChannel;

    SessionService sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionService(
                channelRepository, sessionRepository, intentRepository,
                eventPublisher, new ChannelsProperties());
    }

    @Test
    void startSession_createsAndPersistsSession() {
        Channel channel = Channel.create(ChannelType.MOBILE_APP,
                Set.of(IntentType.CREDIT_APPLICATION, IntentType.PAYMENT), 30, 10, 100);
        UUID partyId = UUID.randomUUID();
        DeviceContext device = deviceContext("device-1", "iOS", "17.2", "192.168.1.1");

        given(channelRepository.findByChannelType("MOBILE_APP")).willReturn(Optional.of(channel));
        given(sessionRepository.findActiveByPartyIdAndChannelType(partyId, "MOBILE_APP")).willReturn(List.of());
        given(sessionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Session result = sessionService.startSession("MOBILE_APP", partyId, device);

        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        assertThat(result.getPartyId()).isEqualTo(partyId);
        assertThat(result.getDevice().getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(result.getDevice().getOs()).isEqualTo("iOS");
        assertThat(result.getDevice().getOsVersion()).isEqualTo("17.2");
        verify(eventPublisher).publishSessionStarted(result.getSessionId(), partyId, "MOBILE_APP", device);
    }

    @Test
    void startSession_expiresExistingActiveSession_S03() {
        Channel channel = Channel.create(ChannelType.MOBILE_APP,
                Set.of(IntentType.CREDIT_APPLICATION), 30, 10, 100);
        UUID partyId = UUID.randomUUID();
        DeviceContext oldDevice = deviceContext("old-device", "Android", "13", "10.0.0.1");
        Session existing = Session.start(channel.getChannelId(), "MOBILE_APP", partyId, oldDevice, 30);

        given(channelRepository.findByChannelType("MOBILE_APP")).willReturn(Optional.of(channel));
        given(sessionRepository.findActiveByPartyIdAndChannelType(partyId, "MOBILE_APP"))
                .willReturn(List.of(existing));
        given(intentRepository.findCapturedBySessionId(existing.getSessionId())).willReturn(List.of());
        given(sessionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        DeviceContext newDevice = deviceContext("new-device", "iOS", "17.0", "10.0.0.2");
        sessionService.startSession("MOBILE_APP", partyId, newDevice);

        assertThat(existing.getStatus()).isEqualTo("EXPIRED");
        verify(eventPublisher).publishSessionExpired(eq(existing.getSessionId()),
                eq("SUPERSEDED_BY_NEW_SESSION"), eq(List.of()));
    }

    @Test
    void startSession_failsWhenChannelDisabled_CH01() {
        given(mockChannel.isActive()).willReturn(false);
        given(channelRepository.findByChannelType("MOBILE_APP")).willReturn(Optional.of(mockChannel));

        assertThatThrownBy(() -> sessionService.startSession("MOBILE_APP", UUID.randomUUID(),
                deviceContext("d", "iOS", "17", "1.1.1.1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void startSession_throwsWhenChannelNotFound() {
        given(channelRepository.findByChannelType("UNKNOWN")).willReturn(Optional.empty());

        assertThatThrownBy(() -> sessionService.startSession("UNKNOWN", UUID.randomUUID(),
                deviceContext("d", "iOS", "17", "1.1.1.1")))
                .isInstanceOf(ChannelNotFoundException.class);
    }

    @Test
    void closeSession_closesSessionAndAbandonsPendingIntents() {
        UUID sessionId = UUID.randomUUID();
        DeviceContext device = deviceContext("d", "Windows", "11", "10.0.0.3");
        Session session = Session.start(UUID.randomUUID(), "WEB", UUID.randomUUID(), device, 30);
        CustomerIntent pending = CustomerIntent.capture(sessionId,
                IntentType.CREDIT_APPLICATION, null, null, null);

        given(sessionRepository.findById(sessionId)).willReturn(Optional.of(session));
        given(intentRepository.findCapturedBySessionId(sessionId)).willReturn(List.of(pending));
        given(intentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(sessionRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Session result = sessionService.closeSession(sessionId);

        assertThat(result.getStatus()).isEqualTo("CLOSED");
        assertThat(pending.getStatus()).isEqualTo("ABANDONED");
        verify(eventPublisher).publishIntentAbandoned(pending.getIntentId(), "SESSION_CLOSED");
    }

    private static DeviceContext deviceContext(String deviceId, String os, String osVersion, String ip) {
        return DeviceContext.builder()
                .deviceId(deviceId)
                .deviceType(DeviceType.MOBILE.name())
                .os(os)
                .osVersion(osVersion)
                .appVersion("2.0.0")
                .networkType("WIFI")
                .ipAddress(ip)
                .isTrustedDevice(false)
                .isRooted(false)
                .isEmulator(false)
                .build();
    }
}
