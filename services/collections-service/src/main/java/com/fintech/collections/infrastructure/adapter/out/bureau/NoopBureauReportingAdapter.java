package com.fintech.collections.infrastructure.adapter.out.bureau;

import com.fintech.collections.application.port.out.BureauReportingAdapter;
import com.fintech.collections.domain.BureauEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/** Stub — immediately confirms the report. TODO: integrate real Círculo de Crédito reporting API. */
@Component
public class NoopBureauReportingAdapter implements BureauReportingAdapter {

    private static final Logger log = LoggerFactory.getLogger(NoopBureauReportingAdapter.class);

    @Override
    public String submit(UUID creditAccountId, UUID obligorPartyId, BureauEventType eventType, BigDecimal amount) {
        String ref = "BUREAU-STUB-" + creditAccountId.toString().substring(0, 8).toUpperCase();
        log.info("Bureau reporting stub submit creditAccountId={} eventType={} amount={} ref={}",
                creditAccountId, eventType, amount, ref);
        return ref;
    }
}
