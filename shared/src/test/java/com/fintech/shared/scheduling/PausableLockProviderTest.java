package com.fintech.shared.scheduling;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** While paused, a business cron never gets its lock (ShedLock skips it); a recovery cron always does. */
class PausableLockProviderTest {

    private final List<String> granted = new ArrayList<>();
    private final LockProvider real = configuration -> {
        granted.add(configuration.getName());
        SimpleLock lock = () -> { };
        return Optional.of(lock);
    };

    private static LockConfiguration lock(String name) {
        return new LockConfiguration(Instant.now(), name, Duration.ofMinutes(30), Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("en pausa, el cierre del día de cargos no corre por cron y el monitor de devengo sí")
    void pausesBusinessCronsAndKeepsRecovery() {
        PausableLockProvider paused = new PausableLockProvider(real, Set.of("charges.accrual-monitor", "statement.retry"));

        assertThat(paused.lock(lock("charges.day-close"))).isEmpty();
        assertThat(paused.lock(lock("commission.liquidation"))).isEmpty();
        assertThat(paused.lock(lock("charges.accrual-monitor"))).isPresent();
        assertThat(paused.lock(lock("statement.retry"))).isPresent();
        assertThat(granted).containsExactly("charges.accrual-monitor", "statement.retry");
    }

    @Test
    @DisplayName("la lista de siempre encendidos por defecto son los crones de recuperación")
    void defaultAlwaysOnAreTheRecoveryCrons() {
        assertThat(Set.of(SchedulingLockAutoConfiguration.ALWAYS_ON_DEFAULT.split(",")))
                .containsExactlyInAnyOrder("charges.accrual-monitor", "closing.cutoff-monitor", "closing.cutoff-run",
                        "credit-portfolio.day-close-retry", "statement.retry");
    }

    @Test
    @DisplayName("negar el candado no cuesta nada: 100 mil disparos en menos de medio segundo")
    void performance() {
        PausableLockProvider paused = new PausableLockProvider(real, Set.of());
        LockConfiguration configuration = lock("charges.day-close");
        long began = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            paused.lock(configuration);
        }
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(500);
    }
}
