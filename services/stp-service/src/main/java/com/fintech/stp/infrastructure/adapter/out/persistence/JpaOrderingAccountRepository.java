package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.OrderingAccountRepository;
import com.fintech.stp.domain.OrderingAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaOrderingAccountRepository
        extends JpaRepository<OrderingAccount, UUID>, OrderingAccountRepository {

    @Override
    @Query("""
            SELECT a FROM OrderingAccount a
            WHERE a.companyId = :companyId AND a.defaultAccount = true AND a.active = true
            """)
    Optional<OrderingAccount> findDefaultByCompanyId(@Param("companyId") UUID companyId);

    @Override
    List<OrderingAccount> findByCompanyId(UUID companyId);
}
