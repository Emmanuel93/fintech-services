package com.fintech.collections.domain;

import com.fintech.shared.exception.DomainException;
import java.math.BigDecimal;

public class ForgivenessLimitExceededException extends DomainException {
    public ForgivenessLimitExceededException(BigDecimal requested, BigDecimal max) {
        super("COLLECTIONS_FORGIVENESS_LIMIT_EXCEEDED",
                "Requested forgiveness " + requested + " exceeds max allowed " + max);
    }
}
