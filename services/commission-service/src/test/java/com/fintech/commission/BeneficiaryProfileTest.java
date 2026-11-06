package com.fintech.commission;

import com.fintech.commission.domain.BeneficiaryProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Who a lot is paid to: the RFC says what to withhold, the CLABE where the SPEI goes; without either, it waits. */
class BeneficiaryProfileTest {

    @Test
    @DisplayName("a natural person by the RFC's length; without RFC, by the party type")
    void naturalPerson() {
        BeneficiaryProfile p = BeneficiaryProfile.of(UUID.randomUUID());
        p.updateFiscal("pegj800101ab1", "BUSINESS", "Juana", "612");
        assertThat(p.isNaturalPerson()).isTrue();
        assertThat(p.getRfc()).isEqualTo("PEGJ800101AB1");

        p.updateFiscal("DIS010101AB1", "INDIVIDUAL", "Distribuidora", "601");
        assertThat(p.isNaturalPerson()).isFalse();

        p.updateFiscal(null, "INDIVIDUAL", "Juana", null);
        assertThat(p.isNaturalPerson()).isTrue();
    }

    @Test
    @DisplayName("payable with RFC and CLABE; otherwise it says what is missing")
    void payable() {
        BeneficiaryProfile p = BeneficiaryProfile.of(UUID.randomUUID());
        assertThat(p.isPayable()).isFalse();
        assertThat(p.whyNotPayable()).isEqualTo("sin perfil fiscal (RFC)");

        p.updateFiscal("PEGJ800101AB1", "INDIVIDUAL", "Juana Pérez", "612");
        assertThat(p.whyNotPayable()).isEqualTo("sin cuenta de pago (CLABE)");

        p.updatePayoutAccount("646180157000000004", null);
        assertThat(p.isPayable()).isTrue();
        assertThat(p.whyNotPayable()).isNull();
        assertThat(p.getPayoutHolder()).isEqualTo("Juana Pérez");
    }

    @Test
    @DisplayName("a CLABE has 18 digits and its check digit: one the rail would refuse is refused when registered")
    void clabeIsValidated() {
        BeneficiaryProfile p = BeneficiaryProfile.of(UUID.randomUUID());
        assertThatThrownBy(() -> p.updatePayoutAccount("64618015700000000", "Juana"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.updatePayoutAccount("646180157000000005", "Juana"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
