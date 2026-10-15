package com.fintech.creditproduct.kyc;

import com.fintech.creditproduct.domain.Capabilities;
import com.fintech.creditproduct.domain.KycOptions;
import com.fintech.creditproduct.domain.ProductType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3b — la exigencia de KYC es del producto, y su default no puede ser permisivo.
 *
 * <p>Lo que estas pruebas fijan no es el comportamiento feliz sino la <b>polaridad</b>: en opciones
 * de pago, lo que no se declara no se tiene; aquí, lo que no se declara se exige. Invertir esa
 * polaridad por descuido —copiando el patrón de {@code PaymentOptions.none()}— convertiría cada
 * producto viejo del catálogo en uno que registra gente sin verificar, y no habría nada que fallara.
 */
class KycOptionsTest {

    @Test
    @DisplayName("una configuración sin bloque de KYC exige KYC manual, no lo apaga")
    void aMissingBlockRequiresManualKyc() {
        KycOptions resolved = KycOptions.orDefault(null);

        assertThat(resolved.kycRequired()).isTrue();
        assertThat(resolved.amlRequired()).isTrue();
        assertThat(resolved.automationEnabled()).isFalse();
        assertThat(resolved.manualFallbackAllowed()).isTrue();
        assertThat(resolved.hasProvider()).isFalse();
    }

    @Test
    @DisplayName("un producto del catálogo sin bloque declarado hereda ese mismo default")
    void aProductWithoutTheBlockInheritsTheSafeDefault() {
        // Los nueve argumentos: exactamente como lo construyen los seeds anteriores a F3b.
        Capabilities old = new Capabilities(true, false, false, "SELF_USE", false, false, false, false, false);

        assertThat(old.kycOptions().kycRequired()).isTrue();
        assertThat(old.kycOptions().attemptsProvider()).isFalse();
    }

    @Test
    @DisplayName("defaultFor no deja ningún tipo de producto sin exigir KYC")
    void noProductTypeShipsWithoutKyc() {
        for (ProductType type : ProductType.values()) {
            assertThat(Capabilities.defaultFor(type).kycOptions().kycRequired())
                    .as("%s debe exigir KYC por omisión", type)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("no se pregunta al proveedor si el producto no exige KYC, aunque declare automatización")
    void automationWithoutRequirementAsksNobody() {
        KycOptions contradictoria = new KycOptions(false, false, true, "INCODE", true);
        assertThat(contradictoria.attemptsProvider()).isFalse();
    }

    @Test
    @DisplayName("NONE y nulo son «sin proveedor»; un nombre real sí es proveedor")
    void theSentinelIsNotAProvider() {
        assertThat(KycOptions.automatedWith("NONE").hasProvider()).isFalse();
        assertThat(KycOptions.automatedWith(null).hasProvider()).isFalse();
        assertThat(KycOptions.automatedWith("  ").hasProvider()).isFalse();
        assertThat(KycOptions.automatedWith("INCODE").hasProvider()).isTrue();
        assertThat(KycOptions.automatedWith("INCODE").attemptsProvider()).isTrue();
    }

    // ── Las contradicciones, que es lo que el catálogo rechaza ───────────────────────────────

    @Test
    @DisplayName("automatización declarada sin proveedor: se puede configurar y nunca se ejerce")
    void automationWithoutAProviderIsAnInconsistency() {
        var males = new KycOptions(true, true, true, "NONE", true).inconsistencies();
        assertThat(males).singleElement().asString().contains("automationEnabled=true sin proveedor");
    }

    @Test
    @DisplayName("proveedor configurado al que nunca se llama")
    void aProviderThatIsNeverCalledIsAnInconsistency() {
        var males = new KycOptions(true, true, false, "INCODE", true).inconsistencies();
        assertThat(males).singleElement().asString().contains("nunca se llama");
    }

    @Test
    @DisplayName("sin automatización y sin salida humana no queda ningún camino")
    void noPathAtAllIsAnInconsistency() {
        var males = new KycOptions(true, true, false, "NONE", false).inconsistencies();
        assertThat(males).singleElement().asString().contains("ningún camino");
    }

    @Test
    @DisplayName("parámetros de verificación en un producto que no la exige")
    void parametersForSomethingTheProductDoesNotDoAreAnInconsistency() {
        var males = new KycOptions(false, true, false, "NONE", false).inconsistencies();
        assertThat(males).anyMatch(m -> m.contains("algo que este producto no hace"));
    }

    @Test
    @DisplayName("las combinaciones coherentes no reportan nada")
    void theCoherentCombinationsAreSilent() {
        assertThat(KycOptions.requiredManual().inconsistencies()).isEmpty();
        assertThat(KycOptions.automatedWith("INCODE").inconsistencies()).isEmpty();
        assertThat(KycOptions.notRequired().inconsistencies()).isEmpty();
    }

    @Test
    @DisplayName("devuelve todas las contradicciones de una vez, no la primera")
    void allInconsistenciesAtOnce() {
        // Quien corrige una configuración quiere verlas juntas, no descubrir la siguiente en el
        // intento siguiente.
        var males = new KycOptions(false, true, true, "NONE", false).inconsistencies();
        assertThat(males).hasSizeGreaterThan(1);
    }
}
