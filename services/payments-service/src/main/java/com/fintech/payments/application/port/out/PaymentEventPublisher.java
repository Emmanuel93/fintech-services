package com.fintech.payments.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentEventPublisher {
    void publishPaymentApplied(String eventId, UUID creditAccountId, BigDecimal amount, long snapshotVersion);
    void publishPaymentReversed(String eventId, UUID creditAccountId, BigDecimal amount, String reason);
}
