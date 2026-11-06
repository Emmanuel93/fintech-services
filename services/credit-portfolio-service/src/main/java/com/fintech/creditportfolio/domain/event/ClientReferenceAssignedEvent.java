package com.fintech.creditportfolio.domain.event;

import java.util.UUID;

/**
 * {@code credit-portfolio.client-reference-assigned}: a client received his payment reference on a distributor's line.
 * Payments keeps a directory of them: a deposit to her CLABE with his reference lands on his installments.
 */
public record ClientReferenceAssignedEvent(UUID creditAccountId, UUID obligorPartyId, UUID beneficiaryPartyId,
                                           Long paymentReference) {}
