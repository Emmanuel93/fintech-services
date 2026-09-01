package com.fintech.banking.conciliacion;

import com.fintech.banking.domain.BankCloseSeal;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El sello bancario: la ecuación que tiene que cuadrar.
 *
 * <pre>
 * saldo de la cuenta contable  +  partidas en conciliación  ==  saldo del estado de cuenta
 * </pre>
 */
class SelloBancarioTest {

    private static final UUID CUENTA = UUID.randomUUID();
    private static final LocalDate DIA = LocalDate.of(2026, 6, 15);

    private static BankCloseSeal sello(String contable, String banco, String partidas) {
        return BankCloseSeal.calcular(CUENTA, DIA, "DAILY",
                new BigDecimal(contable), new BigDecimal(banco), new BigDecimal(partidas), 10, 9);
    }

    @Test
    @DisplayName("con las partidas declaradas, cuadra")
    void cuadraConPartidas() {
        // El mayor dice 100 000, el banco 105 000, y hay 5 000 de abonos sin identificar.
        BankCloseSeal s = sello("100000.00", "105000.00", "5000.00");

        assertThat(s.cuadra()).isTrue();
        assertThat(s.getDifference()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("sin declarar las partidas, NO cuadra — y esa es la señal")
    void sinPartidasNoCuadra() {
        BankCloseSeal s = sello("100000.00", "105000.00", "0.00");

        assertThat(s.cuadra()).isFalse();
        assertThat(s.getDifference()).isEqualByComparingTo("-5000.00");
    }

    @Test
    @DisplayName("un cargo en la puente RESTA, no suma")
    void elCargoResta() {
        BankStatementLine cargo = BankStatementLine.de(CUENTA, DIA, "DEBIT",
                new BigDecimal("150.00"), "MOV-1", null, "COMISION", "BANCO", null);
        SuspenseEntry partida = SuspenseEntry.de(cargo, "Comisión del banco no registrada");

        // El banco cobró 150 que el mayor todavía no tiene: la puente aporta en negativo.
        assertThat(partida.aportacionAlSaldo()).isEqualByComparingTo("-150.00");

        BankCloseSeal s = BankCloseSeal.calcular(CUENTA, DIA, "DAILY",
                new BigDecimal("100000.00"), new BigDecimal("99850.00"),
                partida.aportacionAlSaldo(), 1, 0);
        assertThat(s.cuadra()).isTrue();
    }

    @Test
    @DisplayName("cero con escalas distintas cuadra igual")
    void escalasDistintas() {
        // `BigDecimal.equals` diría que 0 y 0.00 son distintos. Es el mismo error que hacía que
        // ningún sello del cierre se leyera íntegro al releerlo de la base.
        BankCloseSeal s = BankCloseSeal.calcular(CUENTA, DIA, "DAILY",
                new BigDecimal("1000.0000"), new BigDecimal("1000.00"), BigDecimal.ZERO, 0, 0);

        assertThat(s.cuadra()).isTrue();
    }

    @Test
    @DisplayName("una partida se resuelve o se da de baja, y sólo una vez")
    void elCicloDeLaPartida() {
        BankStatementLine abono = BankStatementLine.de(CUENTA, DIA, "CREDIT",
                new BigDecimal("5000.00"), "MOV-2", null, "REF", "JUAN", null);
        SuspenseEntry p = SuspenseEntry.de(abono, "Abono sin identificar");

        assertThat(p.estaAbierta()).isTrue();
        p.resolver("ana.tesoreria");
        assertThat(p.estaAbierta()).isFalse();
        assertThat(p.getResolvedBy()).isEqualTo("ana.tesoreria");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.resolver("otro"))
                .isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.darDeBaja("otro"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("un importe negativo no es un movimiento bancario válido")
    void importeNegativo() {
        // El signo lo lleva `direction`. Un importe negativo con dirección CREDIT es ambiguo, y la
        // ambigüedad en conciliación se paga cara.
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                BankStatementLine.de(CUENTA, DIA, "CREDIT", new BigDecimal("-100"), "X", null,
                        null, null, null))
                .hasMessageContaining("es positivo");
    }
}
