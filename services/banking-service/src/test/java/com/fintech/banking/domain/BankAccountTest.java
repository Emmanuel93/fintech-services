package com.fintech.banking.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankAccountTest {

    private static Clabe clabe() {
        String base = "01218000123456789";
        return Clabe.of(base + Clabe.digitoVerificador(base));
    }

    private static BankAccount cuenta() {
        return BankAccount.alta(UUID.randomUUID(), "012", "BBVA", clabe(),
                "FINTECH SA DE CV", "FIN200101ABC", "MXN", "1101", "2109", "1109", "CL-001");
    }

    @Test
    @DisplayName("nace ACTIVE y declara sus TRES cuentas del mayor")
    void naceOperando() {
        BankAccount c = cuenta();
        assertThat(c.getStatus()).isEqualTo(BankAccountStatus.ACTIVE);
        assertThat(c.puedeOperar()).isTrue();
        // Las tres son necesarias para que el sello cuadre: saldo + partidas == estado de cuenta.
        assertThat(c.getLedgerAccount()).isEqualTo("1101");
        assertThat(c.getSuspenseCreditAccount()).isEqualTo("2109");
        assertThat(c.getSuspenseDebitAccount()).isEqualTo("1109");
    }

    @Test
    @DisplayName("suspender saca la cuenta del ruteo SIN borrar su historia")
    void suspenderNoBorra() {
        BankAccount c = cuenta();
        c.suspender();

        assertThat(c.puedeOperar()).isFalse();
        // Lo que ya salió por ella se sigue conciliando durante meses: el id y la CLABE se quedan.
        assertThat(c.getId()).isNotNull();
        assertThat(c.getClabe()).hasSize(18);

        c.reactivar();
        assertThat(c.puedeOperar()).isTrue();
    }

    @Test
    @DisplayName("una cuenta CLOSED no vuelve: reabrirla sería otra cuenta")
    void cerradaNoVuelve() {
        BankAccount c = cuenta();
        c.cerrar();

        assertThatThrownBy(c::reactivar).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(c::suspender).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sin titular o sin cuenta contable el alta no procede")
    void exigeLoIndispensable() {
        assertThatThrownBy(() -> BankAccount.alta(null, "012", "BBVA", clabe(),
                "  ", null, "MXN", "1101", "2109", "1109", null))
                .hasMessageContaining("titular");

        assertThatThrownBy(() -> BankAccount.alta(null, "012", "BBVA", clabe(),
                "FINTECH", null, "MXN", null, "2109", "1109", null))
                .hasMessageContaining("cuenta contable");
    }

    @Test
    @DisplayName("la CLABE sólo se expone enmascarada")
    void clabeEnmascarada() {
        BankAccount c = cuenta();
        assertThat(c.getClabeEnmascarada()).hasSize(8).startsWith("****");
    }
}
