package com.fintech.creditportfolio.domain.beneficiary;

/**
 * Why a client is on the red list.
 *
 * <ul>
 *   <li>{@code VOUCHER_IN_ARREARS}: a billed installment of his passed its due date unpaid (neither he with his
 *       reference nor his distributor with her delivery). Derived from his plans; it lifts itself the day he is current.</li>
 *   <li>{@code DISTRIBUTOR_IN_ARREARS}: the line of a distributor he owes is blocked by arrears. Derived from the line;
 *       it lifts itself when she pays what is overdue.</li>
 *   <li>{@code DISTRIBUTOR_REPORT}: his distributor reported him, with a reason from the catalog and her comments.
 *       Stored; she or the backoffice lift it, with a comment.</li>
 * </ul>
 */
public enum FlagReason {
    VOUCHER_IN_ARREARS, DISTRIBUTOR_IN_ARREARS, DISTRIBUTOR_REPORT
}
