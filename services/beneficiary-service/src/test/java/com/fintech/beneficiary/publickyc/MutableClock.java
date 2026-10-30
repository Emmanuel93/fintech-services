package com.fintech.beneficiary.publickyc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Un reloj que se adelanta a mano: una hora de congelamiento no se prueba esperando una hora. */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) { this.now = start; }

    void advance(Duration d) { now = now.plus(d); }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    @Override public Instant instant() { return now; }
}
