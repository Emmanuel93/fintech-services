package com.fintech.creditproduct.domain;

/** Types of per-product eligibility constraints evaluated at application time. */
public enum EligibilityRuleType {
    MIN_AGE,                    // applicant must be at least N years old
    MAX_AGE,                    // applicant must be at most N years old
    MIN_SCORE,                  // bureau score floor (overrides policy-level minApprovalScore per product)
    MAX_EXISTING_ACTIVE_CREDITS,// how many active credit accounts the party may already have
    MIN_MONTHLY_INCOME,         // minimum declared monthly income (MXN)
    MAX_DEBT_TO_INCOME_RATIO,   // (totalMonthlyDebt / monthlyIncome) ceiling (e.g. 0.40)
    REQUIRED_PARTY_TYPE,        // party must be of a specific partyType (stored as string value)
    MIN_SENIORITY_MONTHS,       // PAYROLL_LOAN: minimum employment seniority in months
    MIN_GROUP_MEMBERS,          // GROUP_LOAN: minimum number of group members
    MAX_GROUP_MEMBERS           // GROUP_LOAN: maximum number of group members
}
