package com.fintech.scoring.application.service;

import java.time.Duration;

/**
 * Cómo se reintenta la consulta al buró ante una falla transitoria (timeout, caída, 5xx, 429).
 *
 * <p>Backoff exponencial con tope: {@code initialBackoff}, luego ×{@code multiplier}, nunca más que
 * {@code maxBackoff}. Con los valores por defecto —3 reintentos desde 1 s, ×2, tope 10 s— la espera
 * total entre intentos es 1 + 2 + 4 = 7 s, más lo que dure cada intento hasta su timeout.
 *
 * @param maxRetries reintentos después del primer intento (3 ⇒ hasta 4 llamadas)
 */
public record BureauRetryPolicy(int maxRetries, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    public BureauRetryPolicy {
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries no puede ser negativo");
        if (multiplier < 1.0) throw new IllegalArgumentException("multiplier debe ser ≥ 1");
    }

    public static BureauRetryPolicy defaults() {
        return new BureauRetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10));
    }

    /** La espera antes del reintento {@code retry} (1 = el primero). */
    public Duration backoffBefore(int retry) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, retry - 1);
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
