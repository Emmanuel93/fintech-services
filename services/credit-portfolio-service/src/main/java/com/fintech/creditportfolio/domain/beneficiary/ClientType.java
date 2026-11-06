package com.fintech.creditportfolio.domain.beneficiary;

/**
 * How a distributor's client is typed when she places him a loan.
 *
 * <p>NUEVO until the portfolio has seen one placement of his, through any distributor, fully paid: every installment
 * PAID, on a line that was neither written off nor forgiven part of its debt (quebranto and quita do not count as
 * paid). From then on REVOLVENTE. It is read from his history, never captured.
 */
public enum ClientType {
    NUEVO, REVOLVENTE
}
