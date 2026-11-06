package com.fintech.shared.banking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SPEI operation date at the edges that matter: the cutoff to the second, weekends, a holiday right after a
 * weekend, the eve of a holiday, the year end, and the hold window of outgoing orders.
 */
class SpeiOperatingDayTest {

    private static final ZoneId CDMX = ZoneId.of("America/Mexico_City");

    /** The bank holidays closing seeds for 2026 and the first ones of 2027 (019-dias-inhabiles-banxico). */
    private static final BankCalendar BANXICO = BankCalendar.weekdaysExcept(Set.of(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 2), LocalDate.of(2026, 3, 16),
            LocalDate.of(2026, 4, 2), LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 9, 16), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 16),
            LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1)));

    private final SpeiOperatingDay spei = new SpeiOperatingDay(BANXICO, CDMX, LocalTime.of(18, 0), Duration.ofMinutes(10));

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(CDMX).toInstant();
    }

    @ParameterizedTest(name = "{0} → {1} ({2})")
    @DisplayName("la fecha de operación de un movimiento SPEI")
    @CsvSource({
            "2026-11-30T17:59:59, 2026-11-30, un segundo antes del corte: el mismo día",
            "2026-11-30T18:00:00, 2026-12-01, en el corte: el siguiente hábil",
            "2026-11-30T18:00:01, 2026-12-01, un segundo después del corte",
            "2026-11-30T19:00:00, 2026-12-01, el ejemplo: pago del 30 a las 19:00 → banco el 1",
            "2026-11-30T23:59:59, 2026-12-01, antes de medianoche sigue siendo el siguiente hábil",
            "2026-12-01T00:05:00, 2026-12-01, a las 00:05 ya es el día siguiente, sin gracia nocturna",
            "2026-07-31T18:30:00, 2026-08-03, viernes después del corte → lunes",
            "2026-10-31T11:00:00, 2026-11-03, sábado: el lunes 2 es inhábil → martes",
            "2026-11-01T22:00:00, 2026-11-03, domingo víspera de inhábil → martes",
            "2026-11-02T10:00:00, 2026-11-03, en día inhábil a cualquier hora → siguiente hábil",
            "2026-09-15T18:30:00, 2026-09-17, víspera de la Independencia después del corte → el 17",
            "2026-09-15T17:00:00, 2026-09-15, víspera de la Independencia antes del corte: el mismo día",
            "2026-04-01T18:30:00, 2026-04-06, miércoles santo después del corte → lunes de pascua",
            "2026-12-31T18:30:00, 2027-01-04, fin de año después del corte → el primer hábil de 2027",
            "2026-12-31T12:00:00, 2026-12-31, el último día del año antes del corte: el mismo día"
    })
    void operationDate(String moment, LocalDate expected, String why) {
        assertThat(spei.operationDate(at(moment))).as(why).isEqualTo(expected);
    }

    @Test
    @DisplayName("la fecha de cartera no tiene corte: es el día calendario en que entró el dinero")
    void portfolioDateHasNoCutoff() {
        assertThat(spei.portfolioDate(at("2026-11-30T19:00:00"))).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(spei.portfolioDate(at("2026-11-30T23:59:59"))).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(spei.portfolioDate(at("2026-12-01T00:05:00"))).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(spei.portfolioDate(at("2026-10-31T11:00:00"))).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Nested
    @DisplayName("retención de órdenes salientes alrededor del corte")
    class Hold {

        @ParameterizedTest(name = "{0} retenida={1}")
        @CsvSource({
                "2026-11-30T17:49:59, false",
                "2026-11-30T17:50:00, true",
                "2026-11-30T18:00:00, true",
                "2026-11-30T18:09:59, true",
                "2026-11-30T18:10:00, false",
                "2026-10-31T17:55:00, false",
                "2026-11-02T17:55:00, false"
        })
        void window(String moment, boolean held) {
            assertThat(spei.isHeld(at(moment))).isEqualTo(held);
        }

        @Test
        @DisplayName("una orden retenida sale al terminar la ventana y toma la fecha del siguiente hábil")
        void heldOrderGoesAtTheEndOfTheWindowWithTheNextDay() {
            Instant registered = at("2026-11-30T17:55:00");
            assertThat(spei.releaseAt(registered)).isEqualTo(at("2026-11-30T18:10:00"));
            assertThat(spei.orderOperationDate(registered)).isEqualTo(LocalDate.of(2026, 12, 1));
            // Not held: it goes at once with its own date.
            Instant morning = at("2026-11-30T10:00:00");
            assertThat(spei.releaseAt(morning)).isEqualTo(morning);
            assertThat(spei.orderOperationDate(morning)).isEqualTo(LocalDate.of(2026, 11, 30));
        }

        @Test
        @DisplayName("un viernes retenido sale con fecha del lunes")
        void heldOnFridayTakesMonday() {
            assertThat(spei.orderOperationDate(at("2026-07-31T17:52:00"))).isEqualTo(LocalDate.of(2026, 8, 3));
        }

        @Test
        @DisplayName("sin retención configurada nada se retiene")
        void zeroHoldNeverHolds() {
            SpeiOperatingDay noHold = new SpeiOperatingDay(BANXICO, CDMX, LocalTime.of(18, 0), Duration.ZERO);
            assertThat(noHold.isHeld(at("2026-11-30T17:59:00"))).isFalse();
            assertThat(noHold.orderOperationDate(at("2026-11-30T17:59:00"))).isEqualTo(LocalDate.of(2026, 11, 30));
        }
    }

    @Test
    @DisplayName("el corte es configurable: a las 17:30 una transferencia de las 17:45 ya es del día siguiente")
    void configurableCutoff() {
        SpeiOperatingDay early = new SpeiOperatingDay(BANXICO, CDMX, LocalTime.of(17, 30), Duration.ofMinutes(10));
        assertThat(early.operationDate(at("2026-11-30T17:45:00"))).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(early.operationDate(at("2026-11-30T17:29:00"))).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    @DisplayName("una configuración que cruza la medianoche o una retención negativa se rechaza al arrancar")
    void invalidConfiguration() {
        assertThatThrownBy(() -> new SpeiOperatingDay(BANXICO, CDMX, LocalTime.of(23, 55), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeiOperatingDay(BANXICO, CDMX, LocalTime.of(18, 0), Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SpeiOperatingDay(BANXICO, CDMX, LocalTime.of(18, 0), Duration.ofHours(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un calendario sin hábiles en un mes es un dato roto y se dice, no se queda en un bucle")
    void brokenCalendarFailsLoudly() {
        SpeiOperatingDay broken = new SpeiOperatingDay(day -> false, CDMX, LocalTime.of(18, 0), Duration.ZERO);
        assertThatThrownBy(() -> broken.operationDate(at("2026-11-30T10:00:00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no tiene un día hábil");
    }

    @Test
    @DisplayName("calcular la fecha de operación no cuesta nada: 100 mil movimientos en menos de medio segundo")
    void performance() {
        Instant start = at("2026-11-30T19:00:00");
        long began = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            spei.operationDate(start.plusSeconds(i * 37L));
        }
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(500);
    }
}
