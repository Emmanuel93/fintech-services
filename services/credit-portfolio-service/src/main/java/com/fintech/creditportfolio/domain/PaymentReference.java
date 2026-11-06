package com.fintech.creditportfolio.domain;

/**
 * The payment reference of a loan: the 7-digit SPEI numeric reference a person types to say which loan they pay. A
 * 6-digit consecutive plus a Luhn check digit, so a mistyped digit names no loan instead of someone else's.
 */
public final class PaymentReference {

    public static final long MIN_BODY = 100_000L;
    public static final long MAX_BODY = 999_999L;

    private PaymentReference() {
    }

    /** The reference of the {@code body}-th loan: the body followed by its check digit. */
    public static long of(long body) {
        if (body < MIN_BODY || body > MAX_BODY) {
            throw new IllegalArgumentException("El consecutivo de referencia de pago está fuera de rango: " + body);
        }
        return body * 10 + luhnCheckDigit(body);
    }

    /** Whether {@code reference} is well formed: 7 digits whose last one checks the other six. */
    public static boolean isValid(long reference) {
        long body = reference / 10;
        return body >= MIN_BODY && body <= MAX_BODY && reference % 10 == luhnCheckDigit(body);
    }

    static int luhnCheckDigit(long body) {
        int sum = 0;
        boolean doubled = true;
        for (long n = body; n > 0; n /= 10) {
            int digit = (int) (n % 10);
            if (doubled) {
                digit *= 2;
                if (digit > 9) digit -= 9;
            }
            sum += digit;
            doubled = !doubled;
        }
        return (10 - sum % 10) % 10;
    }
}
