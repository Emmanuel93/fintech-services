package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.application.port.out.SessionRepository;
import com.fintech.channels.domain.Session;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaSessionAdapter implements SessionRepository {

    private final SpringDataSessionRepository jpa;

    JpaSessionAdapter(SpringDataSessionRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Optional<Session> findById(UUID id) { return jpa.findById(id); }
    @Override public Session save(Session session) { return jpa.save(session); }

    @Override
    public List<Session> findActiveByPartyIdAndChannelType(UUID partyId, String channelType) {
        return jpa.findActiveByPartyIdAndChannelType(partyId, channelType);
    }
}
