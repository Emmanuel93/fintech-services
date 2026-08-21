package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.WriteOffRecord;
import java.util.Optional;
import java.util.UUID;

public interface WriteOffRecordRepository {
    Optional<WriteOffRecord> findById(UUID writeOffId);
    Optional<WriteOffRecord> findByCreditAccountId(UUID creditAccountId);
    /** WO-03: an account cannot be written off twice. */
    boolean existsByCreditAccountId(UUID creditAccountId);
    WriteOffRecord save(WriteOffRecord record);
}
