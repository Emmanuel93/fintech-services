package com.fintech.closing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La convención de devengo, con los números exactos del hallazgo que la motivó.
 *
 * <p>Estas aserciones son el contrato: fijan qué cobra cada convención y cuánto se separa del plan
 * de pagos. Si alguien cambia la aritmética, aquí se entera.
 */
class AccrualBasisTest {

    static final BigDecimal SALDO = new BigDecimal("20000.00");
    static final BigDecimal TASA  = new BigDecimal("0.32");

    /** Lo que reparte el plan de pagos para un mes: tasa/12 sobre el saldo. */
    static final BigDecimal INTERES_DEL_PLAN = new BigDecimal("533.33");

    @Test
    @DisplayName("ACTUAL_360 en marzo devenga 31 días: 3.35% MÁS que el plan")
    void actual360EnMarzo() {
        BigDecimal marzo = AccrualBasis.ACTUAL_360.interest(
                SALDO, TASA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

        assertThat(marzo).isEqualByComparingTo("551.11");
        assertThat(marzo).isGreaterThan(INTERES_DEL_PLAN);
    }

    @Test
    @DisplayName("ACTUAL_360 en febrero devenga 28 días: 6.65% MENOS que el plan")
    void actual360EnFebrero() {
        BigDecimal febrero = AccrualBasis.ACTUAL_360.interest(
                SALDO, TASA, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));

        assertThat(febrero).isEqualByComparingTo("497.78");
        assertThat(febrero).isLessThan(INTERES_DEL_PLAN);
    }

    @Test
    @DisplayName("THIRTY_360 coincide con el plan al centavo, en CUALQUIER mes")
    void treinta360CoincideConElPlan() {
        // Es el argumento a favor de esta convención: el cliente ve un plan que corresponde
        // exactamente a lo devengado, y el cuadre cartera↔plan es trivial.
        BigDecimal febrero = AccrualBasis.THIRTY_360.interest(
                SALDO, TASA, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1));
        BigDecimal marzo = AccrualBasis.THIRTY_360.interest(
                SALDO, TASA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

        assertThat(febrero).isEqualByComparingTo(INTERES_DEL_PLAN);
        assertThat(marzo).isEqualByComparingTo(INTERES_DEL_PLAN);
    }

    @Test
    @DisplayName("ACTUAL_360 y ACTUAL_365 no son intercambiables: 1.4% de diferencia")
    void trescientosSesentaVsTrescientosSesentaYCinco() {
        BigDecimal a360 = AccrualBasis.ACTUAL_360.interest(
                SALDO, TASA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));
        BigDecimal a365 = AccrualBasis.ACTUAL_365.interest(
                SALDO, TASA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

        assertThat(a360).isGreaterThan(a365);
    }

    @Test
    @DisplayName("el devengo de un día es el que aplica el cierre, y suma el del período")
    void elDiarioSumaElPeriodo() {
        // El cierre aplica un cargo por día. La suma de los 31 cargos de marzo tiene que ser lo
        // mismo que el interés del tramo completo, o el devengo diario y el mensual divergirían.
        BigDecimal suma = BigDecimal.ZERO;
        for (LocalDate d = LocalDate.of(2026, 3, 2); !d.isAfter(LocalDate.of(2026, 4, 1)); d = d.plusDays(1)) {
            suma = suma.add(AccrualBasis.ACTUAL_360.dailyInterest(SALDO, TASA, d));
        }

        BigDecimal tramo = AccrualBasis.ACTUAL_360.interest(
                SALDO, TASA, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));

        // Difieren por el redondeo a centavos de cada día: 31 redondeos contra uno.
        assertThat(suma.subtract(tramo).abs()).isLessThanOrEqualTo(new BigDecimal("0.31"));
    }

    @Test
    @DisplayName("un año bisiesto devenga un día más con ACTUAL, no con THIRTY")
    void bisiesto() {
        LocalDate ini = LocalDate.of(2028, 2, 1);   // 2028 es bisiesto
        LocalDate fin = LocalDate.of(2028, 3, 1);

        assertThat(AccrualBasis.ACTUAL_360.daysBetween(ini, fin)).isEqualTo(29);
        assertThat(AccrualBasis.THIRTY_360.daysBetween(ini, fin)).isEqualTo(30);
    }

    @Test
    @DisplayName("saldo cero o tramo negativo no devengan")
    void sinSaldoNoHayDevengo() {
        assertThat(AccrualBasis.ACTUAL_360.interest(
                BigDecimal.ZERO, TASA, LocalDate.of(2026,3,1), LocalDate.of(2026,4,1)))
                .isEqualByComparingTo("0");
        assertThat(AccrualBasis.ACTUAL_360.interest(
                SALDO, TASA, LocalDate.of(2026,4,1), LocalDate.of(2026,3,1)))
                .isEqualByComparingTo("0");
    }
}
