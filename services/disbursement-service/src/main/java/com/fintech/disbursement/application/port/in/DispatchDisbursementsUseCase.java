package com.fintech.disbursement.application.port.in;

public interface DispatchDisbursementsUseCase {

    /** @return cuántas órdenes se despacharon en esta corrida */
    int dispatchDue();
}
