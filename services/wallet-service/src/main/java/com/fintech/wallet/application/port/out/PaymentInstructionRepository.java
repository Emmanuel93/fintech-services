package com.fintech.wallet.application.port.out;

import com.fintech.wallet.domain.PaymentInstruction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentInstructionRepository {
    Optional<PaymentInstruction> findById(UUID instructionId);
    List<PaymentInstruction> findByCreditAccountId(UUID creditAccountId);
    // PI-06: one PENDING per (creditAccountId, paymentMethod)
    boolean existsPendingByAccountAndMethod(UUID creditAccountId, String paymentMethod);
    PaymentInstruction save(PaymentInstruction instruction);
}
