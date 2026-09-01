package com.fintech.banking.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseCurrency moneda de las cuentas propias mientras no haya operación en otra divisa
 */
@ConfigurationProperties(prefix = "fintech.banking")
public record BankingProperties(String baseCurrency) {

    public BankingProperties {
        if (baseCurrency == null || baseCurrency.isBlank()) baseCurrency = "MXN";
    }
}
