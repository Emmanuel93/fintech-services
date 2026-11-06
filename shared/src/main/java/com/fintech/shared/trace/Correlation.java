package com.fintech.shared.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The platform's correlation id: one per business flow, from the request or the first message that starts it to
 * the last voucher it causes. It travels in the {@value #HTTP_HEADER} header over HTTP and in the
 * {@value #KAFKA_HEADER} header over Kafka, and sits in the log context ({@value #MDC_KEY}) of every line written
 * while that flow is being handled. A failed message carries it to the failure registry, so a failure can be found
 * by the flow it broke and replayed.
 */
public final class Correlation {

    public static final String HTTP_HEADER = "X-Correlation-Id";
    public static final String KAFKA_HEADER = "x-correlation-id";
    public static final String MDC_KEY = "correlationId";

    /** What a caller may send as an id: no spaces, no line breaks, nothing that could forge a log line. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");

    private Correlation() {}

    /** The correlation id of the flow being handled on this thread, or {@code null} outside any flow. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** {@code candidate} if it is a usable id, {@code null} otherwise (a forged or oversized header is not reused). */
    public static String clean(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return VALID.matcher(trimmed).matches() ? trimmed : null;
    }
}
