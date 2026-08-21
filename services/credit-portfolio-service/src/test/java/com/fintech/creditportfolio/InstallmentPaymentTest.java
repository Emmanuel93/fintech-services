package com.fintech.creditportfolio;

import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.InstallmentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un pago tiene que moverse por el calendario, no sólo por el saldo. Mientras
 * no lo hacía, el plan seguía diciendo "pago 0 de 12" después de pagar — que es
 * justo lo que el cliente mira para saber si su dinero entró.
 */
class InstallmentPaymentTest {

    private static Installment cuota(int numero, String total) {
        return Installment.of(UUID.randomUUID(), numero, LocalDate.now().plusMonths(numero),
                new BigDecimal(total).multiply(new BigDecimal("0.9")),
                new BigDecimal(total).multiply(new BigDecimal("0.1")));
    }

    @Test
    void cubrirla_completa_la_marca_pagada_y_devuelve_el_excedente() {
        Installment i = cuota(1, "1000");

        BigDecimal sobrante = i.applyPayment(new BigDecimal("1500"));

        assertThat(i.getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(sobrante).isEqualByComparingTo("500");
    }

    @Test
    void cubrirla_exacta_no_deja_excedente() {
        Installment i = cuota(1, "1000");
        assertThat(i.applyPayment(new BigDecimal("1000"))).isEqualByComparingTo("0");
        assertThat(i.getStatus()).isEqualTo(InstallmentStatus.PAID);
    }

    @Test
    void cubrirla_a_medias_la_deja_parcial_y_consume_todo() {
        Installment i = cuota(1, "1000");

        BigDecimal sobrante = i.applyPayment(new BigDecimal("400"));

        assertThat(i.getStatus()).isEqualTo(InstallmentStatus.PARTIAL);
        assertThat(sobrante).isEqualByComparingTo("0");
    }

    @Test
    void una_mensualidad_ya_pagada_no_consume_nada() {
        Installment i = cuota(1, "1000");
        i.applyPayment(new BigDecimal("1000"));

        // El excedente tiene que seguir de largo hacia la siguiente, no
        // desaparecer en una cuota que ya estaba cubierta.
        assertThat(i.applyPayment(new BigDecimal("700"))).isEqualByComparingTo("700");
        assertThat(i.getStatus()).isEqualTo(InstallmentStatus.PAID);
    }

    @Test
    void un_importe_no_positivo_no_cambia_nada() {
        Installment i = cuota(1, "1000");

        assertThat(i.applyPayment(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(i.applyPayment(new BigDecimal("-50"))).isEqualByComparingTo("-50");
        assertThat(i.getStatus()).isEqualTo(InstallmentStatus.PENDING);
    }

    @Test
    void un_pago_grande_recorre_el_calendario_de_la_mas_vieja_a_la_mas_nueva() {
        Installment[] plan = { cuota(1, "1000"), cuota(2, "1000"), cuota(3, "1000") };

        BigDecimal restante = new BigDecimal("2300");
        for (Installment i : plan) {
            if (restante.compareTo(BigDecimal.ZERO) <= 0) break;
            restante = i.applyPayment(restante);
        }

        assertThat(plan[0].getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(plan[1].getStatus()).isEqualTo(InstallmentStatus.PAID);
        assertThat(plan[2].getStatus()).isEqualTo(InstallmentStatus.PARTIAL);
        assertThat(restante).isEqualByComparingTo("0");
    }
}
