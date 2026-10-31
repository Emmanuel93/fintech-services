package com.fintech.shared.time;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Publica el {@link BusinessClock} con la zona de {@code fintech.business.zone}.
 *
 * <p>La misma propiedad la leen los crones en su {@code @Scheduled(zone = ...)}: «las 23:00» tiene
 * que ser las 23:00 donde está la operación, y la fecha que el job calcula, la de ese mismo lugar.
 * Si la hora del cron y la fecha que usa salieran de zonas distintas, el job correría a la hora
 * correcta con el día equivocado.
 */
@AutoConfiguration
public class BusinessTimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BusinessClock.class)
    public BusinessClock businessClock(@Value("${fintech.business.zone:" + BusinessClock.DEFAULT_ZONE + "}") String zone) {
        BusinessClock clock = BusinessClock.of(ZoneId.of(zone));
        BusinessClock.install(clock);
        return clock;
    }
}
