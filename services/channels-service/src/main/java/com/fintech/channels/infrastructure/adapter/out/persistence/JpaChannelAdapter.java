package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.application.port.out.ChannelRepository;
import com.fintech.channels.domain.Channel;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaChannelAdapter implements ChannelRepository {

    private final SpringDataChannelRepository jpa;

    JpaChannelAdapter(SpringDataChannelRepository jpa) {
        this.jpa = jpa;
    }

    @Override public Optional<Channel> findById(UUID id) { return jpa.findById(id); }
    @Override public Optional<Channel> findByChannelType(String type) { return jpa.findByChannelType(type); }
    @Override public List<Channel> findAllActive() { return jpa.findAllActive(); }
    @Override public Channel save(Channel channel) { return jpa.save(channel); }
}
