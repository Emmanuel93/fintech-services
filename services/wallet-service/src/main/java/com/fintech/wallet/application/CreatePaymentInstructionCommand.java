package com.fintech.wallet.application;

import com.fintech.wallet.domain.PaymentMethod;
import com.fintech.wallet.domain.PaymentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreatePaymentInstructionCommand(
        UUID creditAccountId,
        UUID obligorPartyId,
        PaymentMethod paymentMethod,
        BigDecimal amount,
        PaymentType paymentType,
        Instant scheduledAt   // nullable — only for DOMICILIACION
) {}
