package com.fintech.stp.application.port.in;

import com.fintech.stp.domain.collection.CreditDecision;
import com.fintech.stp.domain.collection.InboundCredit;
import com.fintech.stp.domain.collection.InboundCreditFacts;
import com.fintech.stp.domain.collection.ReturnCause;

import java.util.Arrays;
import java.util.UUID;

/** STP announces a credit on one of our CLABEs and waits for the answer: keep it or return it. */
public interface ReceiveInboundCreditUseCase {

    CreditReceipt receive(InboundCreditFacts facts);

    /** The answer, and whether the credit had already been seen (STP retries its webhook). */
    record CreditReceipt(UUID inboundCreditId, CreditDecision decision, ReturnCause cause, boolean duplicate) {

        public static CreditReceipt of(InboundCredit credit, boolean duplicate) {
            ReturnCause cause = credit.getReturnCause() == null ? null : Arrays.stream(ReturnCause.values())
                    .filter(c -> c.code() == credit.getReturnCause()).findFirst().orElse(null);
            return new CreditReceipt(credit.getInboundCreditId(), credit.decision(), cause, duplicate);
        }

        public boolean confirmed() {
            return decision == CreditDecision.CONFIRMED;
        }
    }
}
