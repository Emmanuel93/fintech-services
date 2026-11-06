package com.fintech.stp.infrastructure.testsupport;

import com.fintech.shared.scheduling.PendingWork;
import com.fintech.stp.application.port.out.OutboxMessageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The outbox as due work, for the test-support quiescence view: the messages the relay would send now.
 *
 * <p>The order is persisted, and its Banxico date fixed, when its request is consumed; it reaches STP on the relay's
 * tick. A message stays {@code PENDING} until the transaction that sends it commits, so the count holds it the whole
 * time. An order held around the cutoff waits for the hold to pass: it is not due, and does not count until then.
 */
@Component
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
class OutboxPendingWork implements PendingWork {

    private final OutboxMessageRepository outbox;

    OutboxPendingWork(OutboxMessageRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return "stp-outbox";
    }

    @Override
    public long pending() {
        return outbox.countDue();
    }
}
