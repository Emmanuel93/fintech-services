package com.fintech.shared.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    private final Clock clock;

    public BusinessClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
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
}
