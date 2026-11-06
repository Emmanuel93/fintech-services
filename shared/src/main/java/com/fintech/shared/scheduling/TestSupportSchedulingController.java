package com.fintech.shared.scheduling;

import com.fintech.shared.simulation.SimulatorGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Whether this service's business crons are paused: {@code GET /internal/test-support/scheduling}.
 *
 * <p>A deterministic run drives every business job itself, at the simulated moment it belongs to. A cron left on
 * fires on real time in the middle of the run and does that work at a moment nobody chose, and nothing said so: the
 * pause was a start-up flag nobody checked. The run asks every service first and refuses to start if one is not paused.
 *
 * <p>Only published with {@code fintech.test-support.enabled=true}, and never in production.
 */
@RestController
@RequestMapping("/internal/test-support/scheduling")
public class TestSupportSchedulingController {

    private final String service;
    private final boolean paused;
    private final Set<String> alwaysOn;
    private final boolean locked;

    public TestSupportSchedulingController(Environment environment,
                                           @Value("${spring.application.name:unknown}") String service,
                                           @Value("${fintech.scheduling.paused:false}") boolean paused,
                                           @Value("${fintech.scheduling.always-on:"
                                                   + SchedulingLockAutoConfiguration.ALWAYS_ON_DEFAULT + "}")
                                           Set<String> alwaysOn,
                                           @Value("${fintech.scheduling.lock.enabled:true}") boolean lockEnabled) {
        SimulatorGuard.requireNonProduction(environment, "estado de los crones", "TEST_SUPPORT_ENABLED=false");
        this.service = service;
        this.paused = paused;
        this.alwaysOn = new TreeSet<>(alwaysOn);
        this.locked = lockEnabled && classPresent("net.javacrumbs.shedlock.core.LockProvider");
    }

    @GetMapping
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("service", service);
        out.put("paused", paused);
        // Without ShedLock a service's crons are not business crons the pause reaches: relays and pollers that move
        // what the run asked for, and the run waits on their results.
        out.put("lockedCrons", locked);
        out.put("alwaysOn", paused && locked ? alwaysOn : Set.of());
        return out;
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, TestSupportSchedulingController.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
