package com.fintech.wallet.infrastructure.adapter.out.persistence;

import com.fintech.wallet.application.port.out.PaymentInstructionRepository;
import com.fintech.wallet.domain.PaymentInstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface JpaPaymentInstructionRepository
        extends JpaRepository<PaymentInstruction, UUID>, PaymentInstructionRepository {

    @Override
    List<PaymentInstruction> findByCreditAccountId(UUID creditAccountId);

    @Override
    @Query("SELECT COUNT(pi) > 0 FROM PaymentInstruction pi " +
           "WHERE pi.creditAccountId = :creditAccountId " +
           "AND pi.paymentMethod = :paymentMethod " +
           "AND pi.status = 'PENDING'")
    boolean existsPendingByAccountAndMethod(
            @Param("creditAccountId") UUID creditAccountId,
            @Param("paymentMethod") String paymentMethod);
}
