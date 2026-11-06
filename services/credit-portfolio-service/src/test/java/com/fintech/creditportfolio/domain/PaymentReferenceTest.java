package com.fintech.creditportfolio.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentReferenceTest {

    @Test
    @DisplayName("la referencia es de 7 dígitos: el consecutivo y su verificador Luhn")
    void sevenDigitsWithLuhn() {
        long reference = PaymentReference.of(100_000L);
        assertThat(String.valueOf(reference)).hasSize(7).startsWith("100000");
        assertThat(PaymentReference.isValid(reference)).isTrue();
        assertThat(PaymentReference.of(999_999L)).isLessThanOrEqualTo(9_999_999L);
    }

    @Test
    @DisplayName("un dígito mal tecleado nunca da otra referencia válida: no se paga el crédito de otro")
    void singleDigitTyposAreCaught() {
        for (long body = 100_000L; body < 101_000L; body++) {
            long reference = PaymentReference.of(body);
            String text = String.valueOf(reference);
            for (int position = 0; position < text.length(); position++) {
                for (char digit = '0'; digit <= '9'; digit++) {
                    if (digit == text.charAt(position) || (position == 0 && digit == '0')) continue;
                    String typo = text.substring(0, position) + digit + text.substring(position + 1);
                    assertThat(PaymentReference.isValid(Long.parseLong(typo))).as("typo %s of %s", typo, text).isFalse();
                }
            }
        }
    }

    @Test
    @DisplayName("fuera del rango no se compone, y todo el rango da referencias distintas en menos de un segundo")
    void rangeAndUniqueness() {
        assertThatThrownBy(() -> PaymentReference.of(99_999L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReference.of(1_000_000L)).isInstanceOf(IllegalArgumentException.class);
        Set<Long> seen = new HashSet<>();
        long began = System.nanoTime();
        for (long body = PaymentReference.MIN_BODY; body <= PaymentReference.MAX_BODY; body++) {
            seen.add(PaymentReference.of(body));
        }
        assertThat(seen).hasSize(900_000);
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(1_000);
    }
}
