package com.fintech.commission.application.port.out;

import java.time.Instant;

/** Each liquidation run as it ran: what period it liquidated, when, and what it did. */
public interface LiquidationRunLog {

    void record(String period, Instant runAt, int lotsOpened, int carriedOver, int waiting, int retried);
}
