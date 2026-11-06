package com.fintech.shared.banking;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * The SPEI operation date ({@code fechaOperacion}) of a transfer: the bank business day on which the bank reports it.
 *
 * <p>The rule, as STP applies it: a transfer made on a business day before the afternoon cutoff (Mexico City time)
 * belongs to that day; from the cutoff on, or on a weekend or a bank holiday, it belongs to the next business day.
 * SPEI runs 24/7, so the money arrives at once; what moves is the date the bank books it on. A transfer at 19:00 on
 * Friday 30 October 2026 is dated Tuesday 3 November (2 November is a bank holiday).
 *
 * <p>Outgoing orders are also held around the cutoff ({@code [cutoff - hold, cutoff + hold)}), while STP changes
 * the operation date: an order registered in that window is sent at {@code cutoff + hold} and takes the next
 * business day. Holding is only done on business days; on a non-business day there is no cutoff to straddle.
 *
 * <p>This rule belongs to the SPEI adapter only. The portfolio dates a payment by the moment the money arrived,
 * with no cutoff; accounting shows the difference between both dates as items in transit.
 *
 * <p>Pure Java and immutable: the calendar and the clock come from outside.
 */
public final class SpeiOperatingDay {

    /** The afternoon cutoff when nobody configures another. */
    public static final LocalTime DEFAULT_CUTOFF = LocalTime.of(18, 0);
    /** How long outgoing orders are held on each side of the cutoff when nobody configures another. */
    public static final Duration DEFAULT_HOLD = Duration.ofMinutes(10);

    private static final Duration MAX_HOLD = Duration.ofHours(2);

    private final BankCalendar calendar;
    private final ZoneId zone;
    private final LocalTime cutoff;
    private final Duration hold;

    public SpeiOperatingDay(BankCalendar calendar, ZoneId zone, LocalTime cutoff, Duration hold) {
        this.calendar = Objects.requireNonNull(calendar, "calendar");
        this.zone     = Objects.requireNonNull(zone, "zone");
        this.cutoff   = Objects.requireNonNull(cutoff, "cutoff");
        this.hold     = Objects.requireNonNull(hold, "hold");
        if (hold.isNegative() || hold.compareTo(MAX_HOLD) > 0) {
            throw new IllegalArgumentException("La retención alrededor del corte debe estar entre 0 y " + MAX_HOLD);
        }
        Duration toMidnight = Duration.between(cutoff, LocalTime.MAX);
        Duration fromMidnight = Duration.between(LocalTime.MIN, cutoff);
        if (hold.compareTo(toMidnight) >= 0 || hold.compareTo(fromMidnight) > 0) {
            throw new IllegalArgumentException("La ventana de retención no puede cruzar la medianoche: corte "
                    + cutoff + ", retención " + hold);
        }
    }

    /** The operation date of a transfer made at {@code at}: incoming credits, settled orders, any SPEI movement. */
    public LocalDate operationDate(Instant at) {
        ZonedDateTime local = Objects.requireNonNull(at, "at").atZone(zone);
        LocalDate day = local.toLocalDate();
        if (calendar.isBusinessDay(day) && local.toLocalTime().isBefore(cutoff)) {
            return day;
        }
        return calendar.nextAfter(day);
    }

    /** Whether an outgoing order registered at {@code at} has to wait for the cutoff to pass. */
    public boolean isHeld(Instant at) {
        if (hold.isZero()) {
            return false;
        }
        ZonedDateTime local = Objects.requireNonNull(at, "at").atZone(zone);
        if (!calendar.isBusinessDay(local.toLocalDate())) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        return !time.isBefore(cutoff.minus(hold)) && time.isBefore(cutoff.plus(hold));
    }

    /** When an outgoing order registered at {@code at} may be sent: at once, or at the end of the hold. */
    public Instant releaseAt(Instant at) {
        if (!isHeld(at)) {
            return at;
        }
        LocalDate day = at.atZone(zone).toLocalDate();
        return day.atTime(cutoff.plus(hold)).atZone(zone).toInstant();
    }

    /** The operation date of an outgoing order registered at {@code at}: the one of the moment it can be sent. */
    public LocalDate orderOperationDate(Instant registeredAt) {
        return operationDate(releaseAt(registeredAt));
    }

    /** The portfolio's date for the same instant: the calendar day in the operation's zone, with no cutoff. */
    public LocalDate portfolioDate(Instant at) {
        return Objects.requireNonNull(at, "at").atZone(zone).toLocalDate();
    }

    public LocalTime cutoff() {
        return cutoff;
    }

    public Duration hold() {
        return hold;
    }

    public ZoneId zone() {
        return zone;
    }
}
