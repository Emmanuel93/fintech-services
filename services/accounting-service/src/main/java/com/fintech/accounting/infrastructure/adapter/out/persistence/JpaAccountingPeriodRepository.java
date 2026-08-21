package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.AccountingPeriodRepository;
import com.fintech.accounting.domain.AccountingPeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface JpaAccountingPeriodRepository
        extends JpaRepository<AccountingPeriod, String>, AccountingPeriodRepository {

    @Override
    @Query("SELECT p FROM AccountingPeriod p ORDER BY p.period DESC")
    List<AccountingPeriod> findAllOrdered();

    @Override
    @Query("""
            SELECT p FROM AccountingPeriod p
             WHERE p.period >= :period AND p.status = com.fintech.accounting.domain.PeriodStatus.OPEN
             ORDER BY p.period ASC
             LIMIT 1
            """)
    Optional<AccountingPeriod> firstOpenFrom(@Param("period") String period);
}
