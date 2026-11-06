package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.DayClose;
import com.fintech.shared.time.BusinessClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The portfolio dates a payment by the moment the money arrived: no cutoff, and a closed day stays closed. */
class PaymentValueDatePolicyTest {

    private static final ZoneId CDMX = ZoneId.of("America/Mexico_City");
    private final DayCloseRepository dayCloses = mock(DayCloseRepository.class);
    private final PaymentValueDatePolicy policy = new PaymentValueDatePolicy(dayCloses);

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(CDMX).toInstant();
    }

    @AfterEach
    void realClock() {
        BusinessClock.current().resetShift();
    }

    @Test
    @DisplayName("recibido a las 19:00 y a las 23:00 de hoy: es de hoy (sin corte en cartera)")
    void todayHasNoCutoff() {
        BusinessClock.current().shiftTo(LocalDateTime.of(2026, 11, 30, 23, 30));
        assertThat(policy.valueDate(at("2026-11-30T19:00:00"))).isNull();
        assertThat(policy.valueDate(at("2026-11-30T23:00:00"))).isNull();
    }

    @Test
    @DisplayName("recibido a las 23:59 y consumido a las 00:02: sigue siendo del día en que llegó, si no ha cerrado")
    void lateConsumptionKeepsTheReceivedDay() {
        BusinessClock.current().shiftTo(LocalDateTime.of(2026, 12, 1, 0, 2));
        when(dayCloses.find(any())).thenReturn(Optional.empty());
        assertThat(policy.valueDate(at("2026-11-30T23:59:00"))).isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    @DisplayName("si el día de recepción ya cerró, el pago tardío va hoy: la foto de un día cerrado no se reescribe")
    void closedDayIsNotReopened() {
        BusinessClock.current().shiftTo(LocalDateTime.of(2026, 12, 1, 0, 30));
        LocalDate closed = LocalDate.of(2026, 11, 30);
        when(dayCloses.find(closed)).thenReturn(Optional.of(new DayClose(closed, closed, "DONE", "test", 10, 0, null,
                null, 1, Instant.now(), Instant.now())));
        assertThat(policy.valueDate(at("2026-11-30T23:59:00"))).isNull();
    }

    @Test
    @DisplayName("recibido a las 00:05 de mañana: es de mañana")
    void afterMidnightIsTomorrow() {
        BusinessClock.current().shiftTo(LocalDateTime.of(2026, 12, 1, 0, 6));
        assertThat(policy.valueDate(at("2026-12-01T00:05:00"))).isNull();
    }
}
