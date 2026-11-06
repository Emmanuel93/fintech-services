package com.fintech.shared.scheduling;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decorator that refuses the lock of every business cron while they are paused, so ShedLock skips the run; the
 * recovery crons (monitors, retries) keep running.
 *
 * <p>A deterministic simulation drives each business job itself through its test-support endpoint, at the simulated
 * moment it belongs to. A cron firing on real time in the middle of the run (charges closed the day every 15 minutes)
 * did that work at a moment nobody chose. The jobs keep their non-locked entry points ({@code execute}, {@code
 * closeDay}): that is what the test-support endpoints call, so pausing the cron does not pause the job.
 *
 * <p>Never in production: {@link SchedulingLockAutoConfiguration} refuses to start paused there.
 */
public final class PausableLockProvider implements LockProvider {

    private static final Logger log = LoggerFactory.getLogger(PausableLockProvider.class);

    private final LockProvider delegate;
    private final Set<String> alwaysOn;

    public PausableLockProvider(LockProvider delegate, Set<String> alwaysOn) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.alwaysOn = Set.copyOf(alwaysOn);
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration configuration) {
        if (alwaysOn.contains(configuration.getName())) {
            return delegate.lock(configuration);
        }
        log.debug("Cron {} en pausa: lo dispara la simulación en su momento", configuration.getName());
        return Optional.empty();
    }
}
