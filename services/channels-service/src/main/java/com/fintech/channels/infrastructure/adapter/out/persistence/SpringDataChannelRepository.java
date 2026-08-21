package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.domain.Channel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataChannelRepository extends JpaRepository<Channel, UUID> {
    Optional<Channel> findByChannelType(String channelType);

    @Query("SELECT c FROM Channel c WHERE c.status = 'ACTIVE'")
    List<Channel> findAllActive();
}
