package com.fintech.collections.application;

import java.util.UUID;

/**
 * Where the money of an applied payment came from, as payments reports it. Collections does not use it: it passes it
 * on with a recovery, so accounting books the recovery against the account the money is in (a SPEI credit waits in
 * transit until the bank credits it) and pairs it with the received credit that paid it.
 *
 * @param fundingSource   {@code SPEI_CREDIT}, {@code CREDIT_BALANCE}, {@code DIRECT}; {@code UNKNOWN} from older producers
 * @param inboundCreditId the SPEI credit received on a collection CLABE that paid it, when it was one
 */
public record PaymentOrigin(String fundingSource, UUID inboundCreditId) {

    public static final String UNKNOWN = "UNKNOWN";

    public PaymentOrigin {
        fundingSource = fundingSource == null || fundingSource.isBlank() ? UNKNOWN : fundingSource;
    }

    /** A payment whose producer does not say where its money came from. */
    public static PaymentOrigin unknown() {
        return new PaymentOrigin(UNKNOWN, null);
    }
}
