package com.fintech.risk.application;

import com.fintech.risk.domain.DelinquencyBucket;

import java.math.BigDecimal;
import java.util.Map;

/** Crea (o versiona) una ProvisionPolicy — una tasa por cada uno de los 7 buckets (PP-02). */
public record CreateProvisionPolicyCommand(
        String productType,
        Map<DelinquencyBucket, BigDecimal> rates
) {}
