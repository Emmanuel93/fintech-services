package com.fintech.creditportfolio.application.port.out;

/** The consecutive of payment references; transactional with the loan it names. */
public interface PaymentReferenceSequence {

    long nextBody();
}
