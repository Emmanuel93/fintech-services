package com.fintech.wallet.infrastructure.adapter.out.dispatch;

import com.fintech.wallet.application.port.out.WalletDispatchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Stub — immediately confirms the withdrawal. TODO: integrate real SPEI/CoDi adapter. */
@Component
public class NoopWalletDispatchAdapter implements WalletDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(NoopWalletDispatchAdapter.class);

    @Override
    public String dispatch(UUID withdrawalId, BigDecimal amount, String payeeAccount) {
        String ref = "WALLET-WD-STUB-" + withdrawalId.toString().substring(0, 8).toUpperCase();
        log.info("Wallet withdrawal stub dispatch withdrawalId={} amount={} payeeAccount={} ref={}",
                withdrawalId, amount, payeeAccount, ref);
        return ref;
    }
}
