package com.fintech.shared.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * El reloj del negocio: qué día es <b>en la operación</b>, no en el contenedor.
 *
 * <h2>Por qué existe</h2>
 *
 * Los contenedores corren en UTC y está bien que así sea: un instante se guarda en UTC y se
 * compara sin ambigüedad. Lo que no puede salir del contenedor es <b>la fecha de negocio</b>. Con
 * {@code LocalDate.now()} a secas, un job que corre a las 00:01 de UTC —las 18:01 de la Ciudad de
 * México— cree que ya es mañana: publicaba como vencidas cuotas que vencían al día siguiente, y el
 * devengo corría a las 17:00 de México, antes de que terminara el día que devengaba.
 *
 * <h2>Por qué una región y no un desfase fijo</h2>
 *
 * Se configura {@code America/Mexico_City} y no {@code -06:00}. México quitó el horario de verano
 * en 2022, así que hoy dan lo mismo; pero si la regla vuelve a cambiar, la región lo absorbe con la
 * actualización de tzdata del JDK y el desfase fijo se queda mal sin que nadie lo note.
 *
 * <p>Se prefiere inyectarlo: así una prueba puede fijar el día y la hora. {@link #current()} queda
 * para el dominio, que no recibe colaboradores.
 */
public final class BusinessClock {

    /** La zona de la operación cuando nadie configura otra. */
    public static final String DEFAULT_ZONE = "America/Mexico_City";

    /**
     * El reloj del proceso, para el código que no recibe colaboradores: entidades y objetos de
     * dominio que hoy llamaban a {@code LocalDate.now()}. Lo instala la autoconfiguración al
     * arrancar con la zona de {@code fintech.business.zone}; antes de eso —una prueba unitaria sin
     * Spring— vale la zona por defecto. Donde se pueda inyectar, se inyecta: es lo que deja a una
     * prueba fijar el día.
     */
    private static volatile BusinessClock current = of(ZoneId.of(DEFAULT_ZONE));

    /** The real clock; {@link #clock} is the one read, and differs only while a shift is in place. */
    private final Clock base;
    private volatile Clock clock;
    /** The {@link Clock} view handed to APIs that want one; it follows every shift (see {@link #asClock()}). */
    private final Clock view;

    public BusinessClock(Clock clock) {
        this.base  = Objects.requireNonNull(clock, "clock");
        this.clock = this.base;
        this.view  = new FollowingClock(this, null);
    }

    /**
     * Shifts the clock so that "today" is {@code today}, with the time of day still running. Meant for
     * test environments: it lets many day closes run without waiting each night or moving data
     * backwards. Exposed by {@code /internal/test-support/clock}, which only exists with test support
     * enabled and never in production ({@code SimulatorGuard}).
     */
    public void shiftTo(LocalDate today) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(base), Objects.requireNonNull(today, "today"));
        this.clock = days == 0 ? base : Clock.offset(base, Duration.ofDays(days));
    }

    /**
     * Shifts the clock so that "now" is {@code at}, a date and a time of day in the operation's zone, with the clock
     * running from there. This is what makes a simulated day deterministic: the operations of the day happen at 10:00
     * whatever the hour the run is started, an after-cutoff payment at 18:30, and the night at 00:05 of the next day.
     * Shifting only the date kept the real time of day, so the same run gave other SPEI operation dates depending on
     * when it was launched.
     */
    public void shiftTo(LocalDateTime at) {
        Instant target = Objects.requireNonNull(at, "at").atZone(base.getZone()).toInstant();
        Duration offset = Duration.between(base.instant(), target);
        this.clock = offset.isZero() ? base : Clock.offset(base, offset);
    }

    /** Back to the real clock. */
    public void resetShift() {
        this.clock = base;
    }

    /** How many days the clock is shifted from the real one; zero when there is no shift. */
    public long shiftedDays() {
        return clock == base ? 0 : Duration.between(base.instant(), clock.instant()).toDays();
    }

    public static BusinessClock of(ZoneId zone) {
        return new BusinessClock(Clock.system(zone));
    }

    /** El reloj configurado del proceso (ver {@link #current}). */
    public static BusinessClock current() {
        return current;
    }

    static void install(BusinessClock clock) {
        current = Objects.requireNonNull(clock, "clock");
    }

    /** La fecha de negocio de hoy, en la zona de la operación. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** El instante actual; no depende de la zona. */
    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    /** El reloj subyacente, para APIs que lo piden ({@code LocalDate.now(Clock)}, etc.). */
    public Clock clock() {
        return clock;
    }

    /**
     * A {@link Clock} that always reads this business clock, shifts included. {@link #clock()} returns a snapshot: a
     * bean built from it keeps the time it had when it was taken, and a service whose timestamps came from a snapshot
     * and whose dates came from the business clock wrote facts that disagreed with each other. Inject this view
     * wherever a component wants a {@code java.time.Clock}.
     */
    public Clock asClock() {
        return view;
    }

    /** The view: every read goes to the business clock in force, so a later shift is seen at once. */
    private static final class FollowingClock extends Clock {

        private final BusinessClock owner;
        private final ZoneId zone;

        private FollowingClock(BusinessClock owner, ZoneId zone) {
            this.owner = owner;
            this.zone  = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone != null ? zone : owner.clock.getZone();
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new FollowingClock(owner, Objects.requireNonNull(newZone, "zone"));
        }

        @Override
        public Instant instant() {
            return owner.clock.instant();
        }

        @Override
        public long millis() {
            return owner.clock.millis();
        }
    }
}
