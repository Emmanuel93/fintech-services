package com.fintech.statement.application.port.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.statement.application.port.out.StatementSource.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementSourceContractTest {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("la CLABE para pagar es la de cobro que manda cartera («collectionClabe»), la de la foto se relee igual")
    void theClabeToPayIsThePortfoliosCollectionClabe() throws Exception {
        Account fromPortfolio = json.readValue("""
                {"contractNumber": "CTR-202504-1338A2B4", "collectionClabe": "646180378000000032", "daysDelinquent": 0}
                """, Account.class);

        assertThat(fromPortfolio.clabe()).isEqualTo("646180378000000032");
        // The statement's own snapshot keeps it as «clabe»: regenerating a document reads back the same CLABE.
        Account fromSnapshot = json.readValue(json.writeValueAsString(fromPortfolio), Account.class);
        assertThat(fromSnapshot.clabe()).isEqualTo("646180378000000032");
    }
}
