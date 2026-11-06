package com.fintech.stp.infrastructure.config;

import com.fintech.shared.banking.BankCalendar;
import com.fintech.shared.banking.SpeiOperatingDay;
import com.fintech.shared.time.BusinessClock;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.domain.collection.CreditAcceptancePolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(StpProperties.class)
public class StpModuleConfig {

    /**
     * The connector's only clock: the business clock's view, so it follows every shift.
     *
     * <p>It was {@code Clock.systemDefaultZone()}: the container's zone (UTC dated an order sent at 19:00 for tomorrow)
     * and blind to the business day, while the operation date came from the business clock — an outbox, a poller and a
     * stub on one clock and the dates on another. Key custody is the only exception: a signing key's validity is real
     * time and stays on it ({@code CachingSigningKeyProvider}).
     */
    @Bean
    public Clock clock(BusinessClock businessClock) {
        return businessClock.asClock();
    }

    /** Which credits received on our CLABEs are kept and which go back to the payer, decided while STP waits. */
    @Bean
    public CreditAcceptancePolicy creditAcceptancePolicy(StpProperties properties) {
        return CreditAcceptancePolicy.standard(properties.getCollection().getAllowedPaymentTypes());
    }

    /** The SPEI operation date and the hold of outgoing orders around the cutoff, on the connector's bank calendar. */
    @Bean
    public SpeiOperatingDay speiOperatingDay(BankCalendar calendar, BusinessClock businessClock, StpProperties properties) {
        return new SpeiOperatingDay(calendar, businessClock.zone(),
                properties.getOperatingDay().getCutoff(), properties.getOperatingDay().getHold());
    }
}
