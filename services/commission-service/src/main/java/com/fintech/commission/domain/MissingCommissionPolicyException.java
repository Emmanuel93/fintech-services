package com.fintech.commission.domain;

import com.fintech.shared.exception.DomainException;

/**
 * CM-06: no hay CommissionPolicy ACTIVE para (productType, commissionType[, distributor]) —
 * nunca se inventa una tasa. El caller debe registrar el hueco y omitir el devengo.
 */
public class MissingCommissionPolicyException extends DomainException {
    public MissingCommissionPolicyException(String productType, String commissionType) {
        super("COMMISSION_MISSING_POLICY",
                "No ACTIVE CommissionPolicy for productType=" + productType + " commissionType=" + commissionType);
    }
}
