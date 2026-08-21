package com.fintech.beneficiary;

import com.fintech.beneficiary.application.port.out.IdentityVerificationGateway.VerificationOutcome;
import com.fintech.beneficiary.application.port.out.KycProviderPort;
import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.IdentityVerificationMode;
import com.fintech.beneficiary.domain.VerificationSource;
import com.fintech.beneficiary.infrastructure.adapter.out.verification.ConfigurableIdentityVerificationGateway;
import com.fintech.beneficiary.infrastructure.adapter.out.verification.UnavailableKycProviderAdapter;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La política de verificación y —sobre todo— su <b>degradación</b>.
 *
 * <p>Lo que estas pruebas protegen no es un camino feliz sino una promesa: <b>ninguna rama rompe el
 * flujo</b>. Un proveedor de KYC es un tercero y se cae; cuando eso pase, la colocación tiene que
 * seguir avanzando por el camino de siempre —una persona— y no quedarse atorada. El costo de la
 * caída debe ser trabajo de analista, no colocaciones detenidas.
 */
class IdentityVerificationPolicyTest {

    private static final UUID PLACEMENT = UUID.randomUUID();
    private static final UUID PROSPECT  = UUID.randomUUID();

    private BeneficiaryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new BeneficiaryProperties();
        properties.getIdentityVerification().setThresholds(new java.util.LinkedHashMap<>(
                Map.of("facialMatch", 0.90, "liveness", 0.90)));
    }

    private ConfigurableIdentityVerificationGateway gatewayWith(KycProviderPort provider) {
        return new ConfigurableIdentityVerificationGateway(properties, provider);
    }

    /** Un proveedor de mentiras que contesta lo que el test necesite. */
    private static KycProviderPort provider(KycProviderPort.Assessment assessment) {
        return new KycProviderPort() {
            @Override public Assessment assess(UUID p, UUID pr) { return assessment; }
            @Override public String providerName() { return "INCODE"; }
        };
    }

    // ── Modo manual ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("configurado en MANUAL — el modo vigente, sin contrato de KYC")
    class ManualMode {

        @Test
        @DisplayName("todo va a revisión humana y no se llama a nadie")
        void everythingGoesToAHuman() {
            properties.getIdentityVerification().setMode(IdentityVerificationMode.MANUAL);

            // Un proveedor que explota si lo tocan: en manual no debe tocarse.
            KycProviderPort noLlamar = new KycProviderPort() {
                @Override public Assessment assess(UUID p, UUID pr) {
                    throw new AssertionError("En modo MANUAL no se debe consultar al proveedor");
                }
                @Override public String providerName() { return "NO_LLAMAR"; }
            };

            VerificationOutcome outcome = gatewayWith(noLlamar).evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.decision()).isEqualTo(IdentityDecision.PENDING);
            // El motivo viaja: la cola tiene que decir por qué está ahí.
            assertThat(outcome.reasonSummary()).isEqualTo("REVISION_MANUAL_CONFIGURADA");
        }

        @Test
        @DisplayName("la estrategia se puede consultar, para que la consola la diga en vez de adivinarla")
        void theStrategyIsVisible() {
            properties.getIdentityVerification().setMode(IdentityVerificationMode.MANUAL);
            assertThat(gatewayWith(new UnavailableKycProviderAdapter()).describeStrategy()).isEqualTo("MANUAL");
        }
    }

    // ── Modo automático ──────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("configurado en AUTOMATIC — el analista recibe sólo las excepciones")
    class AutomaticMode {

        @BeforeEach
        void enableAutomatic() {
            properties.getIdentityVerification().setMode(IdentityVerificationMode.AUTOMATIC);
        }

        @Test
        @DisplayName("con todos los umbrales cumplidos verifica solo, sin tocar a una persona")
        void allThresholdsMetVerifiesAutomatically() {
            var outcome = gatewayWith(provider(KycProviderPort.Assessment.of(
                    Map.of("facialMatch", 0.97, "liveness", 0.95), List.of())))
                    .evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isFalse();
            assertThat(outcome.decision()).isEqualTo(IdentityDecision.VERIFIED);
            assertThat(outcome.source()).isEqualTo(VerificationSource.PROVIDER);
        }

        @Test
        @DisplayName("un umbral no alcanzado va a la cola, y dice cuál y por cuánto")
        void belowThresholdGoesToTheQueue() {
            var outcome = gatewayWith(provider(KycProviderPort.Assessment.of(
                    Map.of("facialMatch", 0.82, "liveness", 0.95), List.of())))
                    .evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.reasonSummary()).contains("UMBRAL_NO_ALCANZADO", "facialMatch", "0.82", "0.9");
        }

        @Test
        @DisplayName("una métrica configurada que el proveedor no reporta cuenta como no alcanzada")
        void anUnreportedMetricIsNotAPass() {
            // No saber no es aprobar: si se pidió medir la prueba de vida y no vino, no se sabe si
            // pasó — y no saberlo es exactamente el caso que va a una persona.
            var outcome = gatewayWith(provider(KycProviderPort.Assessment.of(
                    Map.of("facialMatch", 0.99), List.of())))
                    .evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.reasonSummary()).contains("UMBRAL_SIN_MEDIR", "liveness");
        }

        @Test
        @DisplayName("un documento que el proveedor no pudo validar va a la cola")
        void aFailedDocumentCheckGoesToTheQueue() {
            var outcome = gatewayWith(provider(KycProviderPort.Assessment.of(
                    Map.of("facialMatch", 0.99, "liveness", 0.99), List.of("INE_ILEGIBLE"))))
                    .evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.reasonSummary()).contains("DOCUMENTO_NO_VALIDADO", "INE_ILEGIBLE");
        }

        // ── Resiliencia ──────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("proveedor caído → revisión manual, NO se rompe el flujo")
        void anUnavailableProviderFallsBackToManual() {
            var outcome = gatewayWith(provider(
                    KycProviderPort.Assessment.unavailable("timeout tras 3 reintentos")))
                    .evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.reasonSummary()).contains("PROVEEDOR_NO_DISPONIBLE", "timeout");
        }

        @Test
        @DisplayName("un proveedor que lanza tampoco tumba la colocación")
        void aThrowingProviderIsContained() {
            KycProviderPort malPortado = new KycProviderPort() {
                @Override public Assessment assess(UUID p, UUID pr) {
                    throw new IllegalStateException("connection reset");
                }
                @Override public String providerName() { return "INCODE"; }
            };

            // El puerto promete no lanzar, pero un adaptador nuevo podría olvidarlo. Que eso tumbe
            // una colocación es justo lo que el bloque de contención impide.
            var outcome = gatewayWith(malPortado).evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.reasonSummary()).contains("PROVEEDOR_ERROR", "connection reset");
        }

        @Test
        @DisplayName("sin adaptador de proveedor se degrada igual — el servicio arranca y opera")
        void withoutAProviderItStillWorks() {
            // Es el estado real hoy: modo automático encendido y sin contrato. **No revienta**: la
            // bandera controla el flujo, no el arranque.
            var outcome = gatewayWith(new UnavailableKycProviderAdapter()).evaluate(PLACEMENT, PROSPECT);

            assertThat(outcome.requiresHumanReview()).isTrue();
            assertThat(outcome.reasonSummary()).contains("PROVEEDOR_NO_DISPONIBLE", "Sin contrato");
        }

        @Test
        @DisplayName("ninguna rama devuelve nunca un veredicto sin decisión")
        void everyBranchProducesAnOutcome() {
            List<KycProviderPort.Assessment> casos = List.of(
                    KycProviderPort.Assessment.unavailable("caído"),
                    KycProviderPort.Assessment.of(Map.of(), List.of()),
                    KycProviderPort.Assessment.of(Map.of("facialMatch", 0.5), List.of("X")),
                    KycProviderPort.Assessment.of(Map.of("facialMatch", 0.99, "liveness", 0.99), List.of()));

            for (var caso : casos) {
                var outcome = gatewayWith(provider(caso)).evaluate(PLACEMENT, PROSPECT);
                assertThat(outcome.decision()).isNotNull();
                // Todo lo que va a una persona lleva motivo. Un caso sin explicación en la cola es
                // un caso que el analista tiene que revisar de cero.
                if (outcome.requiresHumanReview()) {
                    assertThat(outcome.reasonSummary()).isNotBlank();
                }
            }
        }
    }
}
