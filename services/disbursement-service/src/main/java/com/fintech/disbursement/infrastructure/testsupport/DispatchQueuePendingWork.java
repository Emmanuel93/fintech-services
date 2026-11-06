package com.fintech.disbursement.infrastructure.testsupport;

import com.fintech.disbursement.application.port.in.DispatchDisbursementsUseCase;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.shared.scheduling.PendingWork;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The dispatch queue as due work, for the test-support quiescence view: the orders the dispatcher would take now, plus
 * one while a cycle is handing its claim to the provider's topic.
 *
 * <p>An order is requested by a Kafka message, but it leaves on the dispatcher's tick: the lag says the request was
 * consumed, not that the order went out. A commission lot liquidated on the night of the 4th waited for that tick
 * while the run moved the clock to Saturday, and STP dated it Monday the 8th; the other run caught the tick on Friday
 * the 5th (D65). What waits for a later moment (a retry's backoff, a closed window) is not due and does not count.
 */
@Component
@ConditionalOnProperty(name = "fintech.test-support.enabled", havingValue = "true")
class DispatchQueuePendingWork implements PendingWork {

    private final DisbursementOrderRepository orders;
    private final DispatchDisbursementsUseCase dispatcher;

    DispatchQueuePendingWork(DisbursementOrderRepository orders, DispatchDisbursementsUseCase dispatcher) {
        this.orders = orders;
        this.dispatcher = dispatcher;
    }

    @Override
    public String name() {
        return "disbursement-dispatch";
    }

    @Override
    public long pending() {
        return orders.countDueForDispatch() + (dispatcher.dispatching() ? 1 : 0);
    }
}
