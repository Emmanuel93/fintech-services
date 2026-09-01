package com.fintech.charges.application.service;

import com.fintech.charges.domain.AccrualSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buy now pay later: antes de su fecha de arranque, el crédito <b>no devenga</b>.
 *
 * <p>No existía forma de decirlo. {@code needsAccrual} devengaba desde que se creaba el calendario,
 * así que un producto BNPL cobraba interés desde el primer día — exactamente lo contrario de lo que
 * promete.
 */
class BnplNoDevengaAntesDeTiempoTest {

    private static final LocalDate ALTA = LocalDate.of(2026, 6, 1);
    private static final LocalDate ARRANQUE = ALTA.plusDays(30);

    private static AccrualSchedule conBnpl() {
        AccrualSchedule s = AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(),
                "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("20000.00"), new BigDecimal("20000.00"), 3);
        s.arrancarDevengoEl(ARRANQUE);
        return s;
    }

    @Test
    @DisplayName("los treinta días previos NO devengan — ni el último")
    void losTreintaDiasPrevios() {
        AccrualSchedule s = conBnpl();

        for (int dia = 0; dia < 30; dia++) {
            assertThat(s.needsAccrual(ALTA.plusDays(dia)))
                    .as("día %d desde el alta", dia)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("el día de arranque SÍ devenga")
    void elDiaDeArranque() {
        // `isBefore` y no `isEqual`: un tope que excluyera su propio primer día regalaría una
        // jornada de interés en cada crédito con BNPL. Poco por crédito, mucho por cartera.
        assertThat(conBnpl().needsAccrual(ARRANQUE)).isTrue();
    }

    @Test
    @DisplayName("después del arranque devenga con normalidad")
    void despues() {
        assertThat(conBnpl().needsAccrual(ARRANQUE.plusDays(1))).isTrue();
    }

    @Test
    @DisplayName("un crédito SIN BNPL devenga desde el alta, como siempre")
    void sinBnpl() {
        // Nulo significa «desde siempre», que es lo que son todos los créditos existentes. Un
        // default con fecha los pondría a todos a arrancar el día del despliegue.
        AccrualSchedule s = AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(),
                "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                new BigDecimal("20000.00"), new BigDecimal("20000.00"), 3);

        assertThat(s.getAccrualStartDate()).isNull();
        assertThat(s.needsAccrual(ALTA)).isTrue();
    }

    @Test
    @DisplayName("la guarda de BNPL no anula la de idempotencia: un día ya devengado sigue bloqueado")
    void noPisaLaIdempotencia() {
        AccrualSchedule s = conBnpl();
        s.markAccruedFor(ARRANQUE);

        assertThat(s.needsAccrual(ARRANQUE)).isFalse();
        assertThat(s.needsAccrual(ARRANQUE.plusDays(1))).isTrue();
    }
}
