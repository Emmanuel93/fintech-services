package com.fintech.beneficiary.infrastructure.adapter.out.verification;

import com.fintech.beneficiary.application.port.out.IdentityVerificationGateway;
import com.fintech.beneficiary.application.port.out.KycProviderPort;
import com.fintech.beneficiary.domain.IdentityVerificationMode;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La política de verificación: un solo adaptador que honra la bandera y <b>degrada</b>.
 *
 * <p>Es uno y no dos —uno por modo— porque la degradación cruza los dos modos: en automático, todo
 * lo que el proveedor no resuelve termina exactamente donde termina el modo manual. Partirlo en dos
 * beans obligaría a duplicar esa caída o a que un adaptador llamara al otro.
 *
 * <h2>Qué hace en cada caso</h2>
 * <pre>
 * MANUAL      → siempre a revisión humana. No se llama a nadie.
 * AUTOMATIC   → se pregunta al proveedor:
 *                 · caído / sin contrato / timeout → revisión humana  ← RESILIENCIA
 *                 · no pudo validar un documento   → revisión humana
 *                 · algún umbral no alcanzado      → revisión humana
 *                 · todo por encima de umbral      → VERIFIED automático
 * </pre>
 *
 * <p><b>Ninguna rama rompe el flujo.</b> Un proveedor de KYC se cae —es un tercero— y cuando eso
 * pase la colocación tiene que seguir avanzando por el camino de siempre, con una persona. El costo
 * de esa caída es trabajo de analista, no colocaciones atoradas.
 *
 * <p>Y el motivo siempre viaja: el analista abre su cola y ve «facial 0.82 &lt; 0.90» o «proveedor
 * no disponible» antes de abrir el expediente. Sin eso tendría una lista de casos sin pista de qué
 * mirar, que es lo mismo que revisarlos todos de cero.
 */
@Component
public class ConfigurableIdentityVerificationGateway implements IdentityVerificationGateway {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableIdentityVerificationGateway.class);

    private final BeneficiaryProperties properties;
    private final KycProviderPort provider;

    public ConfigurableIdentityVerificationGateway(BeneficiaryProperties properties,
                                                    KycProviderPort provider) {
        this.properties = properties;
        this.provider   = provider;
        log.info("Verificación de identidad en modo {} (proveedor: {})",
                properties.getIdentityVerification().getMode(), provider.providerName());
    }

    @Override
    public VerificationOutcome evaluate(UUID placementId, UUID prospectId) {
        var config = properties.getIdentityVerification();

        if (config.getMode() == IdentityVerificationMode.MANUAL) {
            return VerificationOutcome.requiresHumanReview("REVISION_MANUAL_CONFIGURADA");
        }

        KycProviderPort.Assessment assessment;
        try {
            assessment = provider.assess(placementId, prospectId);
        } catch (RuntimeException ex) {
            // El puerto promete no lanzar, pero un adaptador nuevo podría olvidarlo. Que un
            // proveedor mal portado tumbe una colocación es justo lo que este bloque impide.
            log.warn("El proveedor {} lanzó al evaluar {}: {}", provider.providerName(), placementId, ex.toString());
            return VerificationOutcome.requiresHumanReview(
                    "PROVEEDOR_ERROR: " + provider.providerName() + " — " + ex.getMessage());
        }

        if (!assessment.available()) {
            log.warn("Proveedor {} no disponible para {} — a revisión manual: {}",
                    provider.providerName(), placementId, assessment.detail());
            return VerificationOutcome.requiresHumanReview(
                    "PROVEEDOR_NO_DISPONIBLE: " + nullSafe(assessment.detail()));
        }

        if (!assessment.failedChecks().isEmpty()) {
            return VerificationOutcome.requiresHumanReview(
                    assessment.failedChecks().stream()
                            .map(c -> "DOCUMENTO_NO_VALIDADO: " + c)
                            .toArray(String[]::new));
        }

        List<String> belowThreshold = belowThreshold(assessment.scores(), config.getThresholds());
        if (!belowThreshold.isEmpty()) {
            return VerificationOutcome.requiresHumanReview(belowThreshold.toArray(String[]::new));
        }

        log.info("Identidad verificada automáticamente placementId={} por {}", placementId, provider.providerName());
        return VerificationOutcome.autoVerified(
                "VERIFICADO_POR_" + provider.providerName().toUpperCase(),
                "Umbrales cumplidos: " + assessment.scores());
    }

    /**
     * Qué métricas quedaron por debajo de su umbral.
     *
     * <p>Una métrica **configurada y ausente** en la respuesta también cuenta como no alcanzada: si
     * se pidió medir la prueba de vida y el proveedor no la reportó, no se sabe si pasó — y no
     * saberlo es exactamente el caso que va a una persona.
     */
    private static List<String> belowThreshold(Map<String, Double> scores, Map<String, Double> thresholds) {
        List<String> reasons = new ArrayList<>();
        thresholds.forEach((metric, minimum) -> {
            Double actual = scores.get(metric);
            if (actual == null) {
                reasons.add("UMBRAL_SIN_MEDIR: " + metric);
            } else if (actual < minimum) {
                reasons.add("UMBRAL_NO_ALCANZADO: " + metric + " " + actual + " < " + minimum);
            }
        });
        return reasons;
    }

    @Override
    public String describeStrategy() {
        var config = properties.getIdentityVerification();
        return config.getMode() == IdentityVerificationMode.MANUAL
                ? "MANUAL"
                : "AUTOMATIC(" + provider.providerName() + ")";
    }

    private static String nullSafe(String s) { return s == null ? "sin detalle" : s; }
}
