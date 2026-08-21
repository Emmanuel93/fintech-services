package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.StpCompanyKeyRepository;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompanyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaStpCompanyKeyRepository
        extends JpaRepository<StpCompanyKey, UUID>, StpCompanyKeyRepository {

    @Override
    @Query("""
            SELECT k FROM StpCompanyKey k
            WHERE k.companyId = :companyId AND k.purpose = :#{#purpose.name()} AND k.status = 'ACTIVE'
            """)
    Optional<StpCompanyKey> findActiveByCompanyIdAndPurpose(@Param("companyId") UUID companyId,
                                                            @Param("purpose") KeyPurpose purpose);

    @Override
    List<StpCompanyKey> findByCompanyId(UUID companyId);

    /** KY-07: alertar antes de que caduque una llave en uso. */
    @Override
    @Query("SELECT k FROM StpCompanyKey k WHERE k.status = 'ACTIVE' AND k.validTo < :threshold")
    List<StpCompanyKey> findActiveExpiringBefore(@Param("threshold") Instant threshold);
}
