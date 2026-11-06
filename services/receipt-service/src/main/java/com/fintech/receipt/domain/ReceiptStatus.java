package com.fintech.receipt.domain;

/** PENDING → GENERATING → GENERATED | FAILED; a FAILED one, or one stuck GENERATING, is claimed again. */
public enum ReceiptStatus {
    PENDING, GENERATING, GENERATED, FAILED
}
