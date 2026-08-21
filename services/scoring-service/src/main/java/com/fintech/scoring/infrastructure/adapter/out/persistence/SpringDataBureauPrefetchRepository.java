package com.fintech.scoring.infrastructure.adapter.out.persistence;

import com.fintech.scoring.domain.BureauPrefetch;
import com.fintech.scoring.domain.BureauPrefetchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

interface SpringDataBureauPrefetchRepository extends JpaRepository<BureauPrefetch, UUID> {

    @Query("SELECT COUNT(p) > 0 FROM BureauPrefetch p WHERE p.prospectId = :prospectId " +
           "AND p.status NOT IN ('COMPLETED', 'PARTIAL', 'FAILED')")
    boolean existsActiveByProspectId(@Param("prospectId") UUID prospectId);
}
