package com.fintech.accounting.infrastructure.adapter.in.messaging;

import com.fintech.accounting.application.port.out.SubledgerDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The days the portfolio closed: the month-end close checks the portfolio closed the month's last day, with every
 * account accrued, before it reconciles the ledger against it.
 */
@Component
public class PortfolioDayClosedListener {

    private static final Logger log = LoggerFactory.getLogger(PortfolioDayClosedListener.class);
    private final SubledgerDayRepository days;

    public PortfolioDayClosedListener(SubledgerDayRepository days) {
        this.days = days;
    }

    @KafkaListener(topics = "credit-portfolio.day-closed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "portfolioDayClosedListenerContainerFactory")
    public void onDayClosed(PortfolioDayClosedPayload p) {
        if (p.businessDate() == null) return;
        days.dayClosed(p.businessDate(), p.accounts(), p.heldAccounts());
        log.debug("Cartera cerró el día {} cuentas={} retenidas={}", p.businessDate(), p.accounts(),
                p.heldAccounts() == null ? 0 : p.heldAccounts().size());
    }

    @KafkaListener(topics = "credit-portfolio.account-day-closed",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "portfolioAccountDayClosedListenerContainerFactory")
    public void onAccountReleased(PortfolioAccountDayClosedPayload p) {
        if (p.businessDate() == null || p.creditAccountId() == null) return;
        days.accountReleased(p.businessDate(), p.creditAccountId());
    }
}
