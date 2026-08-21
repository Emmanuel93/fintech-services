package com.fintech.wallet.application.port.out;

import java.math.BigDecimal;
import java.util.UUID;

/** Dispatches a withdrawal out of the wallet — SPEI/CoDi. Mirrors credit-portfolio's SpeiDispatchPort. */
public interface WalletDispatchPort {
    String dispatch(UUID withdrawalId, BigDecimal amount, String payeeAccount);
}
