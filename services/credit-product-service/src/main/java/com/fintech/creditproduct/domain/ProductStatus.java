package com.fintech.creditproduct.domain;

public enum ProductStatus {
    DRAFT,
    ACTIVE,
    /** Temporarily withdrawn — can be re-activated (e.g., seasonal product). */
    INACTIVE,
    /** Superseded by a newer version — keeps full history, never re-activatable. */
    RETIRED,
    /** Legacy alias kept for backward compatibility with older records. */
    DEPRECATED
}
