package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.service.AmortizationEngine;
import com.fintech.creditportfolio.domain.Installment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un plan de meses sin intereses: <b>puro capital</b>.
 *
 * <p>AN-28 encontró que el motor ya lo soporta —{@code fixedPayment} devuelve {@code capital / n}
 * cuando la tasa es cero— pero <b>nada lo fijaba</b>. Una optimización, un refactor del cálculo de
 * la cuota o un cambio de redondeo podían romperlo sin que ninguna prueba se enterara, y el síntoma
 * sería que un cliente al que se le prometió MSI acaba pagando interés.
 */
class PlanMesesSinInteresesTest {

    private final AmortizationEngine motor = new AmortizationEngine();

    private List<Installment> msi(String importe, int plazo) {
        return motor.generate(UUID.randomUUID(), new BigDecimal(importe),
                BigDecimal.ZERO, plazo, "FRENCH", "MONTHLY",
                LocalDate.of(2026, 7, 15), new BigDecimal("0.16"));
    }

    @Test
    @DisplayName("6 MSI de 6 000: seis cuotas de 1 000, sin un peso de interés")
    void seisMesesSinIntereses() {
        List<Installment> plan = msi("6000.00", 6);

        assertThat(plan).hasSize(6);
        assertThat(plan).allSatisfy(c -> {
            assertThat(c.getPrincipalAmount()).isEqualByComparingTo("1000.00");
            assertThat(c.getInterestAmount()).isEqualByComparingTo("0");
            // Sin interés no hay IVA: el IVA de un crédito grava el interés, no el capital.
            assertThat(c.getTaxAmount()).isEqualByComparingTo("0");
        });
    }

    @Test
    @DisplayName("el cliente paga EXACTAMENTE el importe de la compra")
    void pagaLoQueCompro() {
        // Es la definición de un MSI, y lo que un redondeo mal puesto rompería sin avisar.
        BigDecimal total = msi("6000.00", 6).stream()
                .map(Installment::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(total).isEqualByComparingTo("6000.00");
    }

    @Test
    @DisplayName("un importe que no divide exacto reparte los centavos sin perderlos")
    void losCentavosNoSePierden() {
        // 1 000 / 3 = 333.33… El plan tiene que sumar 1 000 exactos de todos modos: el centavo
        // sobrante va a alguna cuota, no al aire.
        BigDecimal total = msi("1000.00", 3).stream()
                .map(Installment::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(total).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("con tasa la cuota SÍ lleva interés — el contraste que hace útil al caso cero")
    void conTasaSiHayInteres() {
        List<Installment> conTasa = motor.generate(UUID.randomUUID(), new BigDecimal("6000.00"),
                new BigDecimal("0.24"), 6, "FRENCH", "MONTHLY",
                LocalDate.of(2026, 7, 15), new BigDecimal("0.16"));

        assertThat(conTasa.get(0).getInterestAmount()).isGreaterThan(BigDecimal.ZERO);
        assertThat(conTasa.get(0).getTaxAmount()).isGreaterThan(BigDecimal.ZERO);
    }
}
