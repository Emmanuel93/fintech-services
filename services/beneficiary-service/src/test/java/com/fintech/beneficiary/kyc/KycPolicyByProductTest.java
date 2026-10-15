package com.fintech.beneficiary.kyc;

import com.fintech.beneficiary.application.port.out.ProductKycPolicyRepository;
import com.fintech.beneficiary.application.service.KycPolicyResolver;
import com.fintech.beneficiary.domain.KycPolicy;
import com.fintech.beneficiary.domain.ProductKycPolicy;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.ProductActivatedListener;
import com.fintech.beneficiary.infrastructure.adapter.in.messaging.ProductActivatedPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F3b — qué KYC se exige lo decide el producto, y cuando no se sabe se exige el más estricto.
 *
 * <p>Antes lo decidía {@code fintech.beneficiary.identity-verification.mode}: una propiedad del
 * servicio, es decir una decisión de despliegue que obligaba a que todos los productos de un
 * ambiente compartieran regla. Lo que estas pruebas fijan es que la regla viaja por producto <b>y
 * que ningún hueco de configuración se traduce en una exención</b>.
 */
class KycPolicyByProductTest {

    /** Repositorio en memoria: la proyección es una tabla trivial y no merece un contenedor. */
    private static final class InMemoryPolicies implements ProductKycPolicyRepository {
        private final Map<String, ProductKycPolicy> rows = new HashMap<>();

        @Override public Optional<ProductKycPolicy> findByProductCode(String productCode) {
            return Optional.ofNullable(rows.get(productCode));
        }
        @Override public ProductKycPolicy save(ProductKycPolicy policy) {
            rows.put(policy.getProductCode(), policy);
            return policy;
        }
    }

    private static ProductActivatedPayload activated(String code, int version,
                                                     ProductActivatedPayload.KycBlock kyc) {
        return new ProductActivatedPayload(code, version, new ProductActivatedPayload.Capabilities(kyc));
    }

    // ── El default seguro ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un producto que el catálogo no ha publicado exige KYC manual")
    void anUnknownProductRequiresManualKyc() {
        KycPolicy policy = new KycPolicyResolver(new InMemoryPolicies()).resolve("NO-PUBLICADO");

        assertThat(policy.kycRequired()).isTrue();
        assertThat(policy.attemptsProvider()).isFalse();
    }

    @Test
    @DisplayName("una colocación anterior a F3b, sin producto guardado, también")
    void aPlacementWithoutAProductRequiresManualKyc() {
        var resolver = new KycPolicyResolver(new InMemoryPolicies());

        assertThat(resolver.resolve((String) null).kycRequired()).isTrue();
        assertThat(resolver.resolve("").kycRequired()).isTrue();
    }

    @Test
    @DisplayName("un producto publicado SIN bloque de KYC no se guarda como «sin KYC»")
    void aProductWithoutTheBlockIsNotAnExemption() {
        // Es el caso de todo el catálogo publicado antes de F3b. Si el listener guardara una fila
        // permisiva, un hueco de configuración se volvería una exención de verificación — y nada
        // fallaría al hacerlo.
        var repo = new InMemoryPolicies();
        new ProductActivatedListener(repo).onProductActivated(activated("VIEJO", 3, null));

        assertThat(new KycPolicyResolver(repo).resolve("VIEJO").kycRequired()).isTrue();
        assertThat(new KycPolicyResolver(repo).resolve("VIEJO").attemptsProvider()).isFalse();
    }

    // ── La política sí viaja ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dos productos del MISMO ambiente pueden tener reglas distintas")
    void twoProductsInTheSameEnvironmentCanDiffer() {
        // Es justo lo que la bandera del servicio hacía imposible, y la razón de F3b.
        var repo = new InMemoryPolicies();
        var listener = new ProductActivatedListener(repo);

        listener.onProductActivated(activated("CON-PROVEEDOR", 1,
                new ProductActivatedPayload.KycBlock(true, true, true, "INCODE", true)));
        listener.onProductActivated(activated("A-MANO", 1,
                new ProductActivatedPayload.KycBlock(true, true, false, "NONE", true)));

        var resolver = new KycPolicyResolver(repo);
        assertThat(resolver.resolve("CON-PROVEEDOR").attemptsProvider()).isTrue();
        assertThat(resolver.resolve("CON-PROVEEDOR").provider()).isEqualTo("INCODE");
        assertThat(resolver.resolve("A-MANO").attemptsProvider()).isFalse();
    }

    @Test
    @DisplayName("una reactivación del catálogo sobrescribe la política, no acumula filas")
    void reactivationOverwrites() {
        var repo = new InMemoryPolicies();
        var listener = new ProductActivatedListener(repo);

        listener.onProductActivated(activated("P", 1,
                new ProductActivatedPayload.KycBlock(true, true, false, "NONE", true)));
        listener.onProductActivated(activated("P", 2,
                new ProductActivatedPayload.KycBlock(true, true, true, "INCODE", true)));

        assertThat(repo.findByProductCode("P")).get()
                .extracting(ProductKycPolicy::getProductVersion).isEqualTo(2);
        assertThat(new KycPolicyResolver(repo).resolve("P").attemptsProvider()).isTrue();
    }

    @Test
    @DisplayName("un evento sin código de producto se ignora sin romper el consumo")
    void anEventWithoutAProductCodeIsIgnored() {
        var repo = new InMemoryPolicies();
        var listener = new ProductActivatedListener(repo);

        listener.onProductActivated(null);
        listener.onProductActivated(activated(null, 1, null));
        listener.onProductActivated(activated("  ", 1, null));

        assertThat(repo.findByProductCode("  ")).isEmpty();
    }

    // ── El invariante del agregado gana ──────────────────────────────────────────────────────

    @Test
    @DisplayName("un producto que declara kycRequired=false NO exime a una colocación")
    void aProductThatSaysNoStillRequiresIt() {
        // `Placement.approve(...)` exige identidad VERIFIED y la base lo repite en
        // `chk_approved_requires_identity`. Obedecer la bandera produciría colocaciones que no se
        // pueden aprobar nunca — o, si alguien quitara la restricción para «arreglarlo»,
        // beneficiarias dadas de alta sin que nadie comprobara que son ellas.
        var repo = new InMemoryPolicies();
        new ProductActivatedListener(repo).onProductActivated(activated("SIN-KYC", 1,
                new ProductActivatedPayload.KycBlock(false, false, false, "NONE", false)));

        KycPolicy applied = new KycPolicyResolver(repo).resolve("SIN-KYC");

        assertThat(applied.kycRequired())
                .as("el invariante del agregado gana sobre la configuración del producto")
                .isTrue();
    }
}
