package com.fintech.creditportfolio.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code credit-portfolio.account-day-closed}: una cuenta que el cierre de su día dejó fuera ya tiene su
 * interés de ese día aplicado y cuadrado. Closing suelta su corte.
 */
public record AccountDayClosedEvent(LocalDate businessDate, UUID creditAccountId, Instant releasedAt) {}
