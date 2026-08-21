package com.fintech.collections.application.port.in;

import com.fintech.collections.application.CreatePaymentPromiseCommand;
import com.fintech.collections.domain.PaymentPromise;

public interface CreatePaymentPromiseUseCase {
    PaymentPromise create(CreatePaymentPromiseCommand command);
}
