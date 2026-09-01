package com.fintech.closing.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClosePhaseTest {

    @Test
    @DisplayName("RECONCILE va primero: nada depende de una fase anterior")
    void reconcileEsLaPrimera() {
        // Devengar sobre una bitácora con huecos produce un número equivocado que después hay que
        // perseguir por toda la cadena. Conciliar primero cuesta una fase; después, un reproceso.
        assertThat(ClosePhase.RECONCILE.isFirst()).isTrue();
        assertThat(ClosePhase.ACCRUAL.requires()).isEqualTo(ClosePhase.RECONCILE);
    }

    @Test
    @DisplayName("la mora espera al devengo, y el sello a que todo esté asentado")
    void lasBarrerasSonDependencias() {
        // Hoy el orden es 23:00 → 23:30 → 23:59 → 01:00. Si el devengo tarda más de 59 minutos,
        // la mora se calcula sobre datos viejos y nada lo detecta.
        assertThat(ClosePhase.DELINQUENCY.requires()).isEqualTo(ClosePhase.ACCRUAL);
        assertThat(ClosePhase.SEAL.requires()).isEqualTo(ClosePhase.POSTING_DRAIN);
    }

    @Test
    @DisplayName("PROPAGATE va después del sello: no se empuja un número que aún puede cambiar")
    void propagarDespuesDeSellar() {
        assertThat(ClosePhase.PROPAGATE.requires()).isEqualTo(ClosePhase.SEAL);
    }

    @Test
    @DisplayName("la cadena diaria está completa y sin ciclos")
    void cadenaDiariaIntegra() {
        var orden = ClosePhase.dailyOrder();
        assertThat(orden).startsWith(ClosePhase.RECONCILE).endsWith(ClosePhase.PROPAGATE);

        for (int i = 1; i < orden.size(); i++) {
            ClosePhase actual = orden.get(i);
            assertThat(orden.subList(0, i))
                    .as("la fase %s exige %s, que tiene que venir antes", actual, actual.requires())
                    .contains(actual.requires());
        }
    }
}
