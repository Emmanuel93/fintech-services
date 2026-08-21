package com.fintech.wallet.infrastructure.config;

import com.fintech.wallet.application.WalletProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WalletProperties.class)
public class WalletModuleConfig {}
