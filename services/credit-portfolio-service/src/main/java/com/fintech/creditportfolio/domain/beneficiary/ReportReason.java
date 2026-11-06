package com.fintech.creditportfolio.domain.beneficiary;

/**
 * The catalog a distributor picks from when she reports a client; her comments carry the rest.
 *
 * <ul>
 *   <li>{@code VOUCHER_UNPAID}: he has not paid her his voucher (she may have covered it to keep her line current)</li>
 *   <li>{@code REFUSES_TO_PAY}: he says he will not pay</li>
 *   <li>{@code NOT_LOCATABLE}: she cannot reach him</li>
 *   <li>{@code MISCONDUCT}: he mistreated her or her collectors</li>
 *   <li>{@code SUSPECTED_FRAUD}: false identity, a loan taken for someone else</li>
 *   <li>{@code OTHER}: anything else, said in the comments</li>
 * </ul>
 */
public enum ReportReason {
    VOUCHER_UNPAID, REFUSES_TO_PAY, NOT_LOCATABLE, MISCONDUCT, SUSPECTED_FRAUD, OTHER
}
