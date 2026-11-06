package com.fintech.payments.domain;

/**
 * Where the money of a payment came from. SPEI_CREDIT: a credit received on the payer's collection CLABE (the bank
 * books it on its SPEI operation date). CREDIT_BALANCE: the payer's balance in favor, money already in the bank.
 * DIRECT: the payments API, for rails whose money comes through other channels.
 */
public enum FundingSource {
    DIRECT, SPEI_CREDIT, CREDIT_BALANCE
}
