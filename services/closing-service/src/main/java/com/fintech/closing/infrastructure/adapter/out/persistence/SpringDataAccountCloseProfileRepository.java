package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.domain.AccountCloseProfile;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface SpringDataAccountCloseProfileRepository extends JpaRepository<AccountCloseProfile, UUID> {

    /** Las cuentas vivas. Terminal no devenga, no corta y no entra a ninguna corrida. */
    @Query("""
            SELECT p FROM AccountCloseProfile p
             WHERE p.status NOT IN ('SETTLED','WRITTEN_OFF','CLOSED')
             ORDER BY p.creditAccountId
            """)
    List<AccountCloseProfile> findActive(Pageable pageable);

    @Query("SELECT COUNT(p) FROM AccountCloseProfile p WHERE p.status NOT IN ('SETTLED','WRITTEN_OFF','CLOSED')")
    long countActive();
}
