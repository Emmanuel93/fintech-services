package com.fintech.charges.infrastructure.adapter.out.messaging;

import java.time.LocalDate;
import java.util.UUID;

/** {@code charges.accrual-due}: una cuenta que devengar en el cierre de un día. Llave = cuenta. */
public record AccrualDueOutboundPayload(LocalDate businessDate, UUID creditAccountId) {}
