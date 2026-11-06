package com.fintech.shared.time;

import com.fintech.shared.simulation.SimulatorGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * The business clock, shiftable from outside.
 *
 * <ul>
 *   <li>{@code POST /internal/test-support/clock?at=2026-10-30T18:30} — now is that moment in the operation's zone,
 *       and the clock runs from there. This is what a deterministic run uses.</li>
 *   <li>{@code POST /internal/test-support/clock?today=2026-11-15} — only the date moves; the time of day keeps the
 *       real one (kept for the tools that only need a date).</li>
 * </ul>
 *
 * <p>Only published with {@code fintech.test-support.enabled=true}, and even then it refuses to start in a
 * production environment. Crons keep the container's real time: what shifts is the date the jobs compute,
 * not when they wake up.
 */
@RestController
@RequestMapping("/internal/test-support/clock")
public class TestSupportClockController {

    private static final Logger log = LoggerFactory.getLogger(TestSupportClockController.class);

    private final BusinessClock clock;

    public TestSupportClockController(BusinessClock clock, Environment environment) {
        SimulatorGuard.requireNonProduction(environment, "reloj de negocio desplazable", "TEST_SUPPORT_ENABLED=false");
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> view() {
        return Map.of("today", clock.today().toString(), "now", clock.now().toString(),
                "local", LocalDateTime.now(clock.clock()).toString(),
                "zone", clock.zone().getId(), "shiftedDays", clock.shiftedDays());
    }

    @PostMapping
    public Map<String, Object> shift(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate today,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime at) {
        if ((today == null) == (at == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Indica exactamente uno: today (fecha) o at (fecha y hora de la operación)");
        }
        if (at != null) {
            clock.shiftTo(at);
        } else {
            clock.shiftTo(today);
        }
        log.warn("[test-support] Reloj de negocio desplazado: ahora es {} en {} ({} días)",
                LocalDateTime.now(clock.clock()), clock.zone().getId(), clock.shiftedDays());
        return view();
    }

    @DeleteMapping
    public Map<String, Object> reset() {
        clock.resetShift();
        log.warn("[test-support] Reloj de negocio de vuelta al real: hoy es {}", clock.today());
        return view();
    }
}
