package com.fintech.shared.scheduling;

import com.fintech.shared.simulation.SimulatorGuard;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

/**
 * Un solo ejecutor por cron, aunque el servicio corra con varias réplicas.
 *
 * <h2>Por qué hacía falta</h2>
 *
 * Cada réplica tiene su propio {@code @Scheduled}. Con dos pods, la corrida de facturación del día 1
 * pedía cada CFDI dos veces y la provisión de riesgo se registraba dos veces. Las claves naturales
 * de charges y commission frenaban el duplicado en la base, pero a costa de hacer el trabajo dos
 * veces; billing y risk no tenían ni eso.
 *
 * <h2>Por qué ShedLock sobre la base del servicio y no el candado de Redis de {@code shared}</h2>
 *
 * El candado de Redis ({@code com.fintech.shared.lock}) existe para el reparto fino del cierre:
 * arrendamientos con renovación por unidad de trabajo. Para «que este cron corra una vez», la base
 * de cada servicio ya está ahí: no hay que traer Redis a siete servicios que hoy no lo usan, y el
 * candado vive en el esquema del dueño del job, como el resto de sus datos.
 *
 * <p>{@code usingDbTime()}: el vencimiento del candado lo decide el reloj de PostgreSQL, no el de
 * cada pod. Dos pods con relojes desfasados no pueden verlo vencido al mismo tiempo.
 *
 * <p>Se activa sola en el servicio que declare las dependencias de ShedLock; la tabla la crea su
 * changelog ({@code <esquema>.shedlock}) y su nombre va en {@code fintech.scheduling.lock.table}.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass({LockProvider.class, JdbcTemplateLockProvider.class, JdbcTemplate.class})
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(name = "fintech.scheduling.lock.enabled", havingValue = "true", matchIfMissing = true)
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingLockAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SchedulingLockAutoConfiguration.class);

    /**
     * The recovery crons: they resume or retry what a business job started and never create a business fact of their
     * own, so they stay on even while the business crons are paused.
     */
    static final String ALWAYS_ON_DEFAULT = "charges.accrual-monitor,closing.cutoff-monitor,closing.cutoff-run,"
            + "credit-portfolio.day-close-retry,statement.retry";

    @Bean
    @ConditionalOnMissingBean(LockProvider.class)
    public LockProvider scheduledJobLockProvider(DataSource dataSource,
                                                 @Value("${fintech.scheduling.lock.table:shedlock}") String table,
                                                 @Value("${fintech.scheduling.paused:false}") boolean paused,
                                                 @Value("${fintech.scheduling.always-on:" + ALWAYS_ON_DEFAULT + "}")
                                                 Set<String> alwaysOn,
                                                 Environment environment) {
        LockProvider jdbc = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(table)
                .usingDbTime()
                .build());
        if (!paused) {
            return jdbc;
        }
        SimulatorGuard.requireNonProduction(environment, "crones de negocio en pausa", "FINTECH_SCHEDULING_PAUSED=false");
        log.warn("Crones de negocio en pausa: los dispara la simulación. Siguen encendidos: {}", alwaysOn);
        return new PausableLockProvider(jdbc, alwaysOn);
    }
}
