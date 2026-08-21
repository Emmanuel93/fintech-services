package com.fintech.charges.infrastructure.adapter.out.persistence;

import com.fintech.charges.application.port.out.ChargeRecordRepository;
import com.fintech.charges.domain.ChargeRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JpaChargeRecordRepository
        extends JpaRepository<ChargeRecord, UUID>, ChargeRecordRepository {

    @Override
    Optional<ChargeRecord> findByLinkedChargeId(UUID linkedChargeId);

    @Override
    List<ChargeRecord> findAllByCreditAccountIdOrderByAccrualDateDesc(UUID creditAccountId);

    @Override
    boolean existsByChargeTypeAndCreditAccountId(String chargeType, UUID creditAccountId);
}
