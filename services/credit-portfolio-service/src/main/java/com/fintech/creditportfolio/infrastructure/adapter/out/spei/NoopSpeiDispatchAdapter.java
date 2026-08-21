package com.fintech.creditportfolio.infrastructure.adapter.out.spei;

import com.fintech.creditportfolio.application.port.out.SpeiDispatchPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Stub — immediately confirms disbursement. TODO: integrate real SPEI/BANXICO adapter. */
@Component
public class NoopSpeiDispatchAdapter implements SpeiDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(NoopSpeiDispatchAdapter.class);

    @Override
    public String dispatch(UUID dispositionId, BigDecimal amount, String clabeAccount) {
        String ref = "SPEI-STUB-" + dispositionId.toString().substring(0, 8).toUpperCase();
        log.info("SPEI stub dispatch dispositionId={} amount={} clabe={} ref={}",
                dispositionId, amount, clabeAccount, ref);
        return ref;
    }
}
