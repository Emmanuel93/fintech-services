package com.fintech.charges.application.service;

import com.fintech.charges.domain.AccrualSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La mora se enciende, se apaga y se cobra <b>sobre lo vencido</b>.
 *
 * <p>Tres defectos vivían juntos aquí y se corrigen juntos:
 *
 * <ol>
 *   <li><b>BK-19</b> — la base era {@code principalBalance}, todo el saldo del crédito.</li>
 *   <li><b>BK-18</b> — nadie encendía la mora: {@code activateMoratorium()} no tenía llamador.</li>
 *   <li><b>BK-20</b> — nadie la apagaba al curarse: {@code clearMoratorium()} tampoco.</li>
 * </ol>
 */
class MoraSobreCapitalVencidoTest {

    private static final BigDecimal SALDO_TOTAL = new BigDecimal("20000.00");
    private static final int GRACIA = 3;

    private static AccrualSchedule calendario() {
        return AccrualSchedule.create(UUID.randomUUID(), UUID.randomUUID(),
                "PERSONAL_LOAN", "INSTALLMENT",
                new BigDecimal("0.24"), new BigDecimal("0.36"),
                SALDO_TOTAL, SALDO_TOTAL, GRACIA);
    }

    @Test
    @DisplayName("la base es el CAPITAL VENCIDO, no el saldo del crédito")
    void laBaseEsLoVencido() {
        AccrualSchedule s = calendario();
        LocalDate vencio = LocalDate.of(2026, 5, 10);

        s.actualizarMora(new BigDecimal("1800.00"), vencio, vencio.plusDays(30), GRACIA);

        // El defecto: con `principalBalance` la base habrían sido $20 000 — once veces más.
        assertThat(s.getOverduePrincipal()).isEqualByComparingTo("1800.00");
        assertThat(s.getPrincipalBalance()).isEqualByComparingTo("20000.00");
        assertThat(s.getOverduePrincipal()).isLessThan(s.getPrincipalBalance());
    }

    @Test
    @DisplayName("dentro de la gracia NO hay mora")
    void dentroDeLaGracia() {
        AccrualSchedule s = calendario();
        LocalDate vencio = LocalDate.of(2026, 5, 10);

        // Día 2 de atraso, con gracia de 3: todavía no corre.
        s.actualizarMora(new BigDecimal("1800.00"), vencio, vencio.plusDays(2), GRACIA);

        assertThat(s.isMoratoriumActive()).isFalse();
    }

    @Test
    @DisplayName("pasada la gracia la mora se enciende, y corre desde el día en que venció la gracia")
    void pasadaLaGracia() {
        AccrualSchedule s = calendario();
        LocalDate vencio = LocalDate.of(2026, 5, 10);

        s.actualizarMora(new BigDecimal("1800.00"), vencio, vencio.plusDays(3), GRACIA);

        assertThat(s.isMoratoriumActive()).isTrue();
        // No desde el vencimiento: desde el vencimiento + gracia. Cobrar los días de gracia sería
        // no tener gracia.
        assertThat(s.getMoratoriumStartDate()).isEqualTo(vencio.plusDays(GRACIA));
    }

    @Test
    @DisplayName("BK-20 · al curarse la cuenta, la mora se APAGA sola")
    void seApagaAlCurar() {
        AccrualSchedule s = calendario();
        LocalDate vencio = LocalDate.of(2026, 5, 10);
        s.actualizarMora(new BigDecimal("1800.00"), vencio, vencio.plusDays(30), GRACIA);
        assertThat(s.isMoratoriumActive()).isTrue();

        // El cliente paga: cartera vuelve a medir y ya no hay capital vencido.
        s.actualizarMora(BigDecimal.ZERO, null, vencio.plusDays(31), GRACIA);

        // Antes nadie la apagaba: una vez encendida seguía devengando para siempre.
        assertThat(s.isMoratoriumActive()).isFalse();
        assertThat(s.getOverduePrincipal()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("cubrir una cuota de dos baja la base pero NO apaga la mora")
    void pagoParcialBajaLaBase() {
        AccrualSchedule s = calendario();
        LocalDate primera = LocalDate.of(2026, 5, 10);
        s.actualizarMora(new BigDecimal("3600.00"), primera, primera.plusDays(40), GRACIA);

        // Paga la más antigua: queda una vencida, y el reloj arranca en la que sigue.
        s.actualizarMora(new BigDecimal("1800.00"), primera.plusMonths(1), primera.plusDays(40), GRACIA);

        assertThat(s.isMoratoriumActive()).isTrue();
        assertThat(s.getOverduePrincipal()).isEqualByComparingTo("1800.00");
        assertThat(s.getOldestDueDate()).isEqualTo(primera.plusMonths(1));
    }

    @Test
    @DisplayName("una cuenta al corriente que nunca tuvo mora sigue sin tenerla")
    void alCorrienteSigueLimpia() {
        AccrualSchedule s = calendario();

        s.actualizarMora(BigDecimal.ZERO, null, LocalDate.of(2026, 5, 10), GRACIA);

        assertThat(s.isMoratoriumActive()).isFalse();
        assertThat(s.getOldestDueDate()).isNull();
    }

    @Test
    @DisplayName("volver a caer en mora tras curarse la vuelve a encender")
    void recae() {
        AccrualSchedule s = calendario();
        LocalDate v1 = LocalDate.of(2026, 5, 10);
        s.actualizarMora(new BigDecimal("1800.00"), v1, v1.plusDays(10), GRACIA);
        s.actualizarMora(BigDecimal.ZERO, null, v1.plusDays(11), GRACIA);

        LocalDate v2 = LocalDate.of(2026, 7, 10);
        s.actualizarMora(new BigDecimal("1800.00"), v2, v2.plusDays(10), GRACIA);

        assertThat(s.isMoratoriumActive()).isTrue();
        assertThat(s.getMoratoriumStartDate()).isEqualTo(v2.plusDays(GRACIA));
    }
}
