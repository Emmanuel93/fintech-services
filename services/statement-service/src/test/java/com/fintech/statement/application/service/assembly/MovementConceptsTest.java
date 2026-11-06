package com.fintech.statement.application.service.assembly;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MovementConceptsTest {

    @ParameterizedTest(name = "{0}")
    @DisplayName("cada tipo de movimiento que emite cartera se imprime en palabras del cliente, nunca como código")
    @ValueSource(strings = {
            "PAYMENT_APPLIED", "PAYMENT_RETURNED", "COMMISSION_NETTED", "COMMISSION_NETTED_REVERSED",
            "DISPOSITION_SELF_USE", "DISPOSITION_THIRD_PARTY_CREDIT", "DISPOSITION_PAYROLL", "DISPOSITION_RETURNED",
            "DISPOSITION_FAILED", "ACTIVATION_DISBURSEMENT", "AGREEMENT_RESTRUCTURE", "AGREEMENT_QUITA_PARCIAL",
            "WRITE_OFF", "CHARGE_OPENING_FEE", "CHARGE_ADMIN_FEE", "CHARGE_PREPAYMENT_FEE", "CHARGE_INSURANCE_PREMIUM",
            "CHARGE_REVERSED", "CHARGE_WAIVED"})
    void everyLedgerTypeHasAConcept(String type) {
        String concept = StatementAssembler.concept(type);

        assertThat(concept).isNotEqualTo(type.replace('_', ' ').toLowerCase(Locale.ROOT));
        assertThat(Character.isUpperCase(concept.charAt(0))).isTrue();
    }

    @Test
    @DisplayName("el descuento neteado se llama como en el resto del estado: descuento por pronto pago")
    void theNettedCommissionIsHerEarlyPaymentDiscount() {
        assertThat(StatementAssembler.concept("COMMISSION_NETTED")).isEqualTo("Descuento por pronto pago aplicado");
        assertThat(StatementAssembler.concept("COMMISSION_NETTED_REVERSED")).isEqualTo("Reverso del descuento por pronto pago");
    }
}
