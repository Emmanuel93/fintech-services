package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.CloseUnit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface SpringDataCloseUnitRepository extends JpaRepository<CloseUnit, UUID> {

    /** SELECT normal — sin FOR UPDATE, sin SKIP LOCKED. La exclusión es del candado de Redis. */
    @Query("SELECT u FROM CloseUnit u WHERE u.runId = :runId AND u.status = 'PENDING' ORDER BY u.unitId")
    List<CloseUnit> findPending(@Param("runId") UUID runId, Pageable pageable);

    @Query(value = """
            SELECT * FROM closing.close_units
             WHERE status = 'CLAIMED' AND lease_expires_at < :now
             ORDER BY lease_expires_at
            """, nativeQuery = true)
    List<CloseUnit> findExpiredLeases(@Param("now") java.sql.Timestamp now, Pageable pageable);

    int countByRunIdAndStatus(UUID runId, String status);
}
