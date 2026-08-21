package com.fintech.channels.application.service;

import com.fintech.channels.application.ChannelsProperties;
import com.fintech.channels.application.port.out.ChannelRepository;
import com.fintech.channels.application.port.out.ChannelsEventPublisher;
import com.fintech.channels.application.port.out.CustomerIntentRepository;
import com.fintech.channels.application.port.out.SessionRepository;
import com.fintech.channels.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SessionService {

    private final ChannelRepository channelRepository;
    private final SessionRepository sessionRepository;
    private final CustomerIntentRepository intentRepository;
    private final ChannelsEventPublisher eventPublisher;
    private final ChannelsProperties properties;

    public SessionService(ChannelRepository channelRepository,
                          SessionRepository sessionRepository,
                          CustomerIntentRepository intentRepository,
                          ChannelsEventPublisher eventPublisher,
                          ChannelsProperties properties) {
        this.channelRepository = channelRepository;
        this.sessionRepository = sessionRepository;
        this.intentRepository  = intentRepository;
        this.eventPublisher    = eventPublisher;
        this.properties        = properties;
    }

    public Session startSession(String channelType, UUID partyId, DeviceContext device) {
        Channel channel = channelRepository.findByChannelType(channelType)
                .orElseThrow(() -> new ChannelNotFoundException(channelType));

        // CH-01: DISABLED channel cannot generate sessions
        if (!channel.isActive()) {
            throw new IllegalStateException("Channel " + channelType + " is not active (CH-01)");
        }

        // S-03: one active session per partyId+channelType — expire previous
        if (partyId != null) {
            List<Session> existing = sessionRepository.findActiveByPartyIdAndChannelType(partyId, channelType);
            for (Session old : existing) {
                List<CustomerIntent> pending = intentRepository.findCapturedBySessionId(old.getSessionId());
                pending.forEach(i -> {
                    i.abandon("SESSION_SUPERSEDED");
                    intentRepository.save(i);
                    eventPublisher.publishIntentAbandoned(i.getIntentId(), "SESSION_SUPERSEDED");
                });
                old.expire();
                sessionRepository.save(old);
                eventPublisher.publishSessionExpired(old.getSessionId(), "SUPERSEDED_BY_NEW_SESSION",
                        pending.stream().map(CustomerIntent::getIntentId).toList());
            }
        }

        Session session = Session.start(channel.getChannelId(), channelType, partyId,
                device, channel.getSessionTtlMinutes());
        sessionRepository.save(session);
        eventPublisher.publishSessionStarted(session.getSessionId(), partyId, channelType, device);
        return session;
    }

    @Transactional(readOnly = true)
    public Session getSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId.toString()));
    }

    public Session closeSession(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException(sessionId.toString()));

        List<CustomerIntent> pending = intentRepository.findCapturedBySessionId(sessionId);
        pending.forEach(i -> {
            i.abandon("SESSION_CLOSED");
            intentRepository.save(i);
            eventPublisher.publishIntentAbandoned(i.getIntentId(), "SESSION_CLOSED");
        });

        session.close();
        sessionRepository.save(session);
        eventPublisher.publishSessionExpired(session.getSessionId(), "CLOSED_BY_USER",
                pending.stream().map(CustomerIntent::getIntentId).toList());
        return session;
    }
}
