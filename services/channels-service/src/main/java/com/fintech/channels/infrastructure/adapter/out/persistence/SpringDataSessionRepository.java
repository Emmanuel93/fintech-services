package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface SpringDataSessionRepository extends JpaRepository<Session, UUID> {

    @Query("SELECT s FROM Session s WHERE s.partyId = :partyId AND s.channelType = :channelType " +
           "AND s.status IN ('ACTIVE', 'IDLE')")
    List<Session> findActiveByPartyIdAndChannelType(@Param("partyId") UUID partyId,
                                                    @Param("channelType") String channelType);
}
