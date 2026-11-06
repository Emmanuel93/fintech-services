package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.InboundCreditRepository;
import com.fintech.stp.domain.collection.InboundCredit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaInboundCreditRepository extends JpaRepository<InboundCredit, UUID>, InboundCreditRepository {

    @Override
    @Query("SELECT c FROM InboundCredit c WHERE c.companyId = :companyId AND c.stpId = :stpId")
    Optional<InboundCredit> findByStpId(@Param("companyId") UUID companyId, @Param("stpId") long stpId);

    @Override
    @Query("""
            SELECT c FROM InboundCredit c
             WHERE c.companyId = :companyId AND c.trackingKey = :trackingKey AND c.operationDate = :operationDate""")
    Optional<InboundCredit> findByTrackingKey(@Param("companyId") UUID companyId,
                                              @Param("trackingKey") String trackingKey,
                                              @Param("operationDate") LocalDate operationDate);

    @Override
    @Query("SELECT c FROM InboundCredit c WHERE c.partyId = :partyId ORDER BY c.receivedAt")
    List<InboundCredit> findByParty(@Param("partyId") UUID partyId);
}
