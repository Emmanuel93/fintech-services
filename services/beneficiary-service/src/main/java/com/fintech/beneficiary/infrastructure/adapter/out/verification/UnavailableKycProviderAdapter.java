package com.fintech.beneficiary.infrastructure.adapter.out.verification;

import com.fintech.beneficiary.application.port.out.KycProviderPort;
import java.util.UUID;

/**
 * El proveedor que no existe todavía. No hay contrato con Incode.
 *
 * <p>Reporta indisponibilidad en vez de fallar: es el mismo camino por el que se degradará el día
 * que el proveedor real esté caído, así que **la ruta de resiliencia se ejerce desde hoy** en vez de
 * ser código que nadie ha corrido hasta la primera caída en producción.
 *
 * <p>Se declara en {@code VerificationConfig} con {@code @ConditionalOnMissingBean}, de modo que el
 * día que aparezca el adaptador real éste se retira solo. Va ahí y no como {@code @Component}
 * porque esa condición sólo es fiable sobre un método {@code @Bean}: durante el escaneo de
 * componentes el orden de evaluación no está definido, y el arranque falla de forma intermitente.
 */
public class UnavailableKycProviderAdapter implements KycProviderPort {

    @Override
    public Assessment assess(UUID placementId, UUID prospectId) {
        return Assessment.unavailable("Sin contrato con proveedor de KYC");
    }

    @Override
    public String providerName() {
        return "NINGUNO";
    }
}
