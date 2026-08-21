package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.BureauEventType;
import java.math.BigDecimal;
import java.util.UUID;

/** Reports a write-off/quita to Círculo de Crédito. Mirrors SpeiDispatchPort/WalletDispatchPort. */
public interface BureauReportingAdapter {
    /** @return the bureau's confirmation reference. Throws if the submission fails (caller marks FAILED). */
    String submit(UUID creditAccountId, UUID obligorPartyId, BureauEventType eventType, BigDecimal amount);
}
