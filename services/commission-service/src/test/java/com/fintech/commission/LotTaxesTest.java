package com.fintech.commission;

import com.fintech.commission.domain.BeneficiaryProfile;
import com.fintech.commission.domain.LotTaxes;
import com.fintech.commission.domain.TaxRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lot's taxes. The beneficiary invoices the commission and 16 % IVA; a company that pays a natural person withholds
 * ISR (10 %, or 1.25 % in RESICO) and two thirds of the IVA, and pays them to the SAT on their behalf.
 */
class LotTaxesTest {

    private static BeneficiaryProfile beneficiary(String rfc, String partyType, String regime) {
        BeneficiaryProfile p = BeneficiaryProfile.of(UUID.randomUUID());
        p.updateFiscal(rfc, partyType, "Distribuidora", regime);
        return p;
    }

    @Test
    @DisplayName("a company (12-character RFC) is paid the gross: nothing is withheld")
    void companyIsPaidTheGross() {
        LotTaxes t = LotTaxes.of(new BigDecimal("1000.00"), new BigDecimal("160.00"),
                beneficiary("DIS010101AB1", "BUSINESS", "601"), TaxRules.MEXICO);

        assertThat(t.isrWithheld()).isEqualByComparingTo("0");
        assertThat(t.ivaWithheld()).isEqualByComparingTo("0");
        assertThat(t.gross()).isEqualByComparingTo("1160.00");
        assertThat(t.net()).isEqualByComparingTo("1160.00");
    }

    @Test
    @DisplayName("a natural person (13-character RFC): 10 % ISR and two thirds of the IVA are withheld")
    void naturalPersonWithholdings() {
        LotTaxes t = LotTaxes.of(new BigDecimal("1000.00"), new BigDecimal("160.00"),
                beneficiary("PEGJ800101AB1", "INDIVIDUAL", "612"), TaxRules.MEXICO);

        assertThat(t.isrWithheld()).isEqualByComparingTo("100.00");
        assertThat(t.ivaWithheld()).isEqualByComparingTo("106.67");
        assertThat(t.net()).isEqualByComparingTo("953.33");
    }

    @Test
    @DisplayName("a natural person in RESICO: 1.25 % ISR")
    void resico() {
        LotTaxes t = LotTaxes.of(new BigDecimal("1000.00"), new BigDecimal("160.00"),
                beneficiary("PEGJ800101AB1", "INDIVIDUAL", "626"), TaxRules.MEXICO);

        assertThat(t.isrWithheld()).isEqualByComparingTo("12.50");
        assertThat(t.ivaWithheld()).isEqualByComparingTo("106.67");
        assertThat(t.net()).isEqualByComparingTo("1040.83");
    }

    @Test
    @DisplayName("gross = net + what is withheld, to the centavo")
    void grossIsNetPlusWithholdings() {
        LotTaxes t = LotTaxes.of(new BigDecimal("1234.57"), new BigDecimal("197.53"),
                beneficiary("PEGJ800101AB1", "INDIVIDUAL", "612"), TaxRules.MEXICO);

        assertThat(t.net().add(t.isrWithheld()).add(t.ivaWithheld())).isEqualByComparingTo(t.gross());
    }
}
