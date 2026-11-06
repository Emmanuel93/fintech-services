package com.fintech.receipt.application.port.out;

import com.fintech.receipt.domain.Receipt;

/** {@code receipt.receipt-generated}: a client's receipt is ready (for notifications and the channels). */
public interface ReceiptEventPublisher {

    void publishGenerated(Receipt receipt);
}
