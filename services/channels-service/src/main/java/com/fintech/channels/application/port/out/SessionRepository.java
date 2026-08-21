package com.fintech.channels.application.port.out;

import com.fintech.channels.domain.Session;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {
    Optional<Session> findById(UUID sessionId);
    List<Session> findActiveByPartyIdAndChannelType(UUID partyId, String channelType);
    Session save(Session session);
}
