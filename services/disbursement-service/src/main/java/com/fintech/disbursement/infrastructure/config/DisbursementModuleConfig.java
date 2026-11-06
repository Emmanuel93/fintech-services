package com.fintech.disbursement.infrastructure.config;

import com.fintech.disbursement.application.DisbursementProperties;
import com.fintech.shared.time.BusinessClock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({DisbursementProperties.class, DisbursementTopicProperties.class})
public class DisbursementModuleConfig {

    /**
     * Injectable clock, so the operating window and the backoff are tested without sleeping the thread.
     *
     * <p>It is the business clock, as in the STP connector: the window, a retry's due moment and the order's timestamps
     * are business time. The system clock kept them on the container's real time: in a run with the business clock
     * shifted, an order fell in or out of the window by the hour the run was launched, and a retry came due by the
     * wall clock while the run's days went by (D66).
     */
    @Bean
    public Clock clock(BusinessClock businessClock) {
        return businessClock.asClock();
    }
}
