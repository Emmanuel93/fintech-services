package com.fintech.disbursement.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingRuleTest {

    private static final UUID EMPRESA_A = UUID.randomUUID();
    private static final UUID EMPRESA_B = UUID.randomUUID();

    @Test
    @DisplayName("una regla sin empresa cubre a todas")
    void regla_por_defecto() {
        RoutingRule regla = RoutingRule.of(null, Rail.SPEI, Provider.STP,
                BigDecimal.ZERO, null, 100);

        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, new BigDecimal("10000"))).isTrue();
        assertThat(regla.covers(EMPRESA_B, Rail.SPEI, new BigDecimal("10000"))).isTrue();
    }

    @Test
    @DisplayName("una regla de empresa no se aplica a otra")
    void regla_especifica() {
        RoutingRule regla = RoutingRule.of(EMPRESA_A, Rail.SPEI, Provider.STP,
                BigDecimal.ZERO, null, 10);

        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, BigDecimal.TEN)).isTrue();
        assertThat(regla.covers(EMPRESA_B, Rail.SPEI, BigDecimal.TEN)).isFalse();
    }

    @Test
    @DisplayName("el rango de monto es inclusivo en ambos extremos")
    void rango_de_monto() {
        RoutingRule regla = RoutingRule.of(null, Rail.SPEI, Provider.STP,
                new BigDecimal("100.00"), new BigDecimal("5000.00"), 50);

        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, new BigDecimal("100.00"))).isTrue();
        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, new BigDecimal("5000.00"))).isTrue();
        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, new BigDecimal("99.99"))).isFalse();
        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, new BigDecimal("5000.01"))).isFalse();
    }

    @Test
    @DisplayName("una regla deshabilitada no cubre nada — apagar un proveedor es un UPDATE")
    void deshabilitada() {
        RoutingRule regla = RoutingRule.of(null, Rail.SPEI, Provider.STP, BigDecimal.ZERO, null, 1);
        regla.disable();

        assertThat(regla.covers(EMPRESA_A, Rail.SPEI, BigDecimal.TEN)).isFalse();
    }

    @Test
    @DisplayName("a igual prioridad manda la específica de empresa")
    void especificidad() {
        RoutingRule especifica = RoutingRule.of(EMPRESA_A, Rail.SPEI, Provider.STP, BigDecimal.ZERO, null, 100);
        RoutingRule generica = RoutingRule.of(null, Rail.SPEI, Provider.STP, BigDecimal.ZERO, null, 100);

        assertThat(especifica.specificity()).isLessThan(generica.specificity());
    }

    @Test
    void el_rail_tiene_que_coincidir() {
        RoutingRule regla = RoutingRule.of(null, Rail.SPEI, Provider.STP, BigDecimal.ZERO, null, 1);
        assertThat(regla.covers(EMPRESA_A, Rail.INTERNAL, BigDecimal.TEN)).isFalse();
    }
}
