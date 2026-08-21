package com.fintech.beneficiary.infrastructure.adapter.out.verification;

import com.fintech.beneficiary.application.port.out.KycProviderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cablea el proveedor de KYC.
 *
 * <p>Mientras no exista contrato, el puerto lo cubre {@link UnavailableKycProviderAdapter}, que
 * reporta indisponibilidad. El día que llegue Incode se registra su adaptador como bean y éste
 * <b>se retira solo</b>: sin tocar la política de verificación, ni el agregado, ni el endpoint de
 * dictamen.
 *
 * <p>La condición va sobre un método {@code @Bean} y no sobre un {@code @Component} porque sólo
 * ahí es fiable — durante el escaneo de componentes el orden de evaluación no está definido y el
 * arranque falla de forma intermitente.
 */
@Configuration
public class VerificationConfig {

    @Bean
    @ConditionalOnMissingBean(KycProviderPort.class)
    public KycProviderPort unavailableKycProvider() {
        return new UnavailableKycProviderAdapter();
    }
}
