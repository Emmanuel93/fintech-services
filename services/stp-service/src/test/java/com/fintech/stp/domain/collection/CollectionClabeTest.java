package com.fintech.stp.domain.collection;

import com.fintech.shared.banking.ClabeCheckDigit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionClabeTest {

    @Test
    @DisplayName("la CLABE de cobro es 646 + 180 + prefijo de cliente + consecutivo de 7 + verificador")
    void composesTheStpRange() {
        CollectionClabe clabe = CollectionClabe.compose("646", "180", "3780", 1);

        // The legacy connector issued exactly this CLABE as the first account of client prefix 3780.
        assertThat(clabe.value()).isEqualTo("646180378000000016");
        assertThat(ClabeCheckDigit.isValid(clabe.value())).isTrue();
        assertThat(CollectionClabe.inRange(clabe.value(), "646", "180", "3780")).isTrue();
        assertThat(CollectionClabe.inRange(clabe.value(), "646", "180", "3781")).isFalse();
    }

    @Test
    @DisplayName("el último consecutivo del rango compone y el siguiente se rechaza")
    void rangeLimits() {
        assertThat(CollectionClabe.compose("646", "180", "3780", 9_999_999L).value()).startsWith("6461803780" + "9999999");
        assertThatThrownBy(() -> CollectionClabe.compose("646", "180", "3780", 10_000_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionClabe.compose("646", "180", "3780", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un prefijo que no es de 4 dígitos no compone: el sembrado '000001' daba CLABEs de 20 dígitos")
    void rejectsMalformedPrefix() {
        assertThatThrownBy(() -> CollectionClabe.compose("646", "180", "000001", 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prefijo de cliente");
        assertThatThrownBy(() -> CollectionClabe.compose("646", "18", "3780", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("cien mil consecutivos dan cien mil CLABEs distintas y válidas, en menos de un segundo")
    void uniqueAndFast() {
        Set<String> seen = new HashSet<>();
        long began = System.nanoTime();
        for (long n = 1; n <= 100_000; n++) {
            String clabe = CollectionClabe.compose("646", "180", "3780", n).value();
            assertThat(seen.add(clabe)).isTrue();
        }
        assertThat((System.nanoTime() - began) / 1_000_000).isLessThan(1_000);
        assertThat(seen).allMatch(ClabeCheckDigit::isValid);
    }
}
