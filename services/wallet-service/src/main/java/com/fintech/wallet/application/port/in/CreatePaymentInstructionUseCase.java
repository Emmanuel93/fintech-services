package com.fintech.wallet.application.port.in;

import com.fintech.wallet.application.CreatePaymentInstructionCommand;
import com.fintech.wallet.domain.PaymentInstruction;

public interface CreatePaymentInstructionUseCase {
    PaymentInstruction create(CreatePaymentInstructionCommand command);
}
