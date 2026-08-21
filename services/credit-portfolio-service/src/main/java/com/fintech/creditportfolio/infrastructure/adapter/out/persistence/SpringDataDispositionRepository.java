package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.Disposition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataDispositionRepository extends JpaRepository<Disposition, UUID> {

    List<Disposition> findByCreditAccountIdIn(java.util.Collection<UUID> creditAccountIds);
    List<Disposition> findByCreditAccountId(UUID creditAccountId);
}
