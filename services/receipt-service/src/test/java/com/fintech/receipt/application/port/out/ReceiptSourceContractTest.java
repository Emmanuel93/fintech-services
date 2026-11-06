package com.fintech.receipt.application.port.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.receipt.application.port.out.ReceiptSource.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptSourceContractTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("la CLABE del recibo es la de cobro de la distribuidora que manda cartera («collectionClabe»)")
    void theReceiptsClabeIsThePortfoliosCollectionClabe() throws Exception {
        Account account = json.readValue("""
                {"contractNumber": "CTR-202504-1338A2B4", "productType": "DISTRIBUTOR_LINE",
                 "collectionClabe": "646180378000000032", "daysDelinquent": 0}
                """, Account.class);

        assertThat(account.clabe()).isEqualTo("646180378000000032");
    }
}
