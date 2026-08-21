package com.fintech.charges.application.port.out;

import com.fintech.charges.domain.ChargeRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargeRecordRepository {
    Optional<ChargeRecord> findById(UUID id);
    Optional<ChargeRecord> findByLinkedChargeId(UUID linkedChargeId);
    List<ChargeRecord> findAllByCreditAccountIdOrderByAccrualDateDesc(UUID creditAccountId);
    boolean existsByChargeTypeAndCreditAccountId(String chargeType, UUID creditAccountId);
    ChargeRecord save(ChargeRecord record);
}
