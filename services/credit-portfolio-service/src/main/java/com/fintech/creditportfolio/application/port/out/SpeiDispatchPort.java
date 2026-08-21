package com.fintech.creditportfolio.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/** ACL — sends SPEI disbursement and returns external reference number. */
public interface SpeiDispatchPort {
    String dispatch(UUID dispositionId, BigDecimal amount, String clabeAccount);
}
