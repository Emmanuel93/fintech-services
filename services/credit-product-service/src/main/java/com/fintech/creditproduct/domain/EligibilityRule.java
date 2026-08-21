package com.fintech.creditproduct.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-product eligibility constraint evaluated by origination before admitting an application.
 *
 * <p>Rules of type {@link EligibilityRuleType#REQUIRED_PARTY_TYPE} use the {@code stringValue}
 * field; all others use {@code thresholdValue}.
 */
@Entity
@Table(
    name = "eligibility_rules",
    schema = "credit_product",
    indexes = {
        @Index(name = "idx_er_product_def", columnList = "product_definition_id")
    }
)
public class EligibilityRule {

    @Id
    @Column(name = "rule_id", nullable = false, updatable = false)
    private UUID ruleId;

    @Column(name = "product_definition_id", nullable = false, updatable = false)
    private UUID productDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 40)
    private EligibilityRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "operator", length = 5)
    private EligibilityOperator operator;

    /** Numeric threshold (null for REQUIRED_PARTY_TYPE). */
    @Column(name = "threshold_value", precision = 19, scale = 4)
    private BigDecimal thresholdValue;

    /** String value used by REQUIRED_PARTY_TYPE (e.g. "DISTRIBUTOR"). */
    @Column(name = "string_value", length = 50)
    private String stringValue;

    /** Human-readable error code returned to origination when rule fails. */
    @Column(name = "error_code", nullable = false, length = 100)
    private String errorCode;

    protected EligibilityRule() {}

    public static EligibilityRule numeric(
            UUID productDefinitionId,
            EligibilityRuleType ruleType,
            EligibilityOperator operator,
            BigDecimal thresholdValue,
            String errorCode) {

        EligibilityRule r = new EligibilityRule();
        r.ruleId              = UUID.randomUUID();
        r.productDefinitionId = productDefinitionId;
        r.ruleType            = ruleType;
        r.operator            = operator;
        r.thresholdValue      = thresholdValue;
        r.errorCode           = errorCode;
        return r;
    }

    public static EligibilityRule partyType(
            UUID productDefinitionId,
            String requiredPartyType,
            String errorCode) {

        EligibilityRule r = new EligibilityRule();
        r.ruleId              = UUID.randomUUID();
        r.productDefinitionId = productDefinitionId;
        r.ruleType            = EligibilityRuleType.REQUIRED_PARTY_TYPE;
        r.stringValue         = requiredPartyType;
        r.errorCode           = errorCode;
        return r;
    }

    public boolean evaluate(BigDecimal value) {
        if (ruleType == EligibilityRuleType.REQUIRED_PARTY_TYPE) {
            throw new UnsupportedOperationException("Use evaluatePartyType for REQUIRED_PARTY_TYPE");
        }
        return operator.apply(value, thresholdValue);
    }

    public boolean evaluatePartyType(String partyType) {
        return stringValue != null && stringValue.equalsIgnoreCase(partyType);
    }

    public UUID getRuleId()                    { return ruleId; }
    public UUID getProductDefinitionId()       { return productDefinitionId; }
    public EligibilityRuleType getRuleType()   { return ruleType; }
    public EligibilityOperator getOperator()   { return operator; }
    public BigDecimal getThresholdValue()      { return thresholdValue; }
    public String getStringValue()             { return stringValue; }
    public String getErrorCode()               { return errorCode; }
}
