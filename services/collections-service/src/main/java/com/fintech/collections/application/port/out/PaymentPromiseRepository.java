package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.PaymentPromise;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPromiseRepository {
    Optional<PaymentPromise> findActiveByCaseId(UUID caseId);
    List<PaymentPromise> findByCaseId(UUID caseId);
    /** For PromiseBrokenCheckJob — active promises whose promisedDate has passed. */
    List<PaymentPromise> findActiveWithPromisedDateBefore(LocalDate date);
    PaymentPromise save(PaymentPromise promise);
}
