package com.fintech.salesorg.domain;

/**
 * How a distributor's client is typed when she places him a loan: it decides which limits of the placement policy
 * apply to him.
 *
 * <p>NUEVO is everyone until the portfolio has seen one placement of his, through any distributor, fully paid
 * (quebranto and quita do not count); from then on he is REVOLVENTE. The portfolio owns the typing; here it is the key
 * of the limits a branch configures for each kind of client.
 */
public enum ClientType {
    NUEVO, REVOLVENTE
}
