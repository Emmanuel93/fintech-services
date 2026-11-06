package com.fintech.disbursement.application.port.in;

public interface DispatchDisbursementsUseCase {

    /** @return cuántas órdenes se despacharon en esta corrida */
    int dispatchDue();

    /**
     * Whether a dispatch cycle is running now. Between the claim, which leaves its orders {@code DISPATCHED}, and
     * their hand-off to the provider's topic, neither the queue nor Kafka's lag holds them: a run that read both in
     * that moment would see the service at rest while an order is still on its way.
     */
    boolean dispatching();
}
