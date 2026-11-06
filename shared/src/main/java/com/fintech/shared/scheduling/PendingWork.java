package com.fintech.shared.scheduling;

/**
 * Work a service keeps in a table for a poller of its own, due now and not done yet: an order its dispatcher has to
 * hand to the provider, a message its relay has to send.
 *
 * <p>Kafka's lag does not see it: the message that brought the work was consumed already. A run that moved the clock
 * while such work waited sent it with another day's date: a commission lot liquidated on the night of the 4th went out
 * on Friday the 5th in one run and on Monday the 8th in the other, because the dispatcher's tick came after the clock
 * had reached Saturday (D65).
 *
 * <p>The test-support quiescence view adds it to the lag: a service is at rest when it consumed everything published
 * to it and holds nothing due. What waits for a later moment (a retry's backoff, an order held around the cutoff) is
 * not due and does not count: it is the clock that has to move for it.
 */
public interface PendingWork {

    /** The queue, as the quiescence view names it. */
    String name();

    /** How many items are due now and not done, the ones a running cycle holds included. */
    long pending();
}
