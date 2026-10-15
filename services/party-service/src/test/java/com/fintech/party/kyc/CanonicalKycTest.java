package com.fintech.party.kyc;

import com.fintech.party.domain.KycProvider;
import com.fintech.party.domain.KycVerification;
import com.fintech.party.domain.VerificationMode;
import com.fintech.party.domain.VerificationSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El KYC es nuestro, no del proveedor.
 *
 * <p>Antes este registro guardaba el veredicto y nada más: ni cómo se intentó resolver, ni quién lo
 * emitió, ni con qué proveedor, ni con qué evidencia. Con un solo proveedor eso se aguanta; con dos
 * deja de ser una respuesta, y el día que uno pregunte «¿qué entrevista fue?» no hay nada que
 * enseñarle.
 *
 * <p>Y es el modelo <b>canónico</b>: cubre a todo el que queda registrado —cliente de uso propio o
 * beneficiaria del modelo distribuidor— porque toda beneficiaria es, o llega a ser, un `party`.
 */
class CanonicalKycTest {

    private static KycVerification build() {
        return KycVerification.create(UUID.randomUUID(), "INE");
    }

    @Test
    @DisplayName("Revisión humana: el proveedor es NONE, y eso es un veredicto completo")
    void revision_humana_deja_proveedor_ninguno() {
        KycVerification v = build();

        v.verify("ana.analista", "doc-ref-1", LocalDate.now().plusYears(5));

        assertThat(v.isVerified()).isTrue();
        assertThat(v.getVerificationSource()).isEqualTo(VerificationSource.MANUAL);
        assertThat(v.getVerificationMode()).isEqualTo(VerificationMode.MANUAL);
        // NONE y no nulo: «lo resolvió una persona» es un hecho, no un dato que falte.
        assertThat(v.getProvider()).isEqualTo(KycProvider.NONE);
        assertThat(v.getProviderReference()).isNull();
    }

    @Test
    @DisplayName("🔑 Veredicto de proveedor: queda CUÁL fue y con qué sesión")
    void veredicto_de_proveedor_guarda_cual_y_con_que_sesion() {
        KycVerification v = build();

        v.verifyWithProvider(KycProvider.INCODE, "interview-abc123",
                Map.of("liveness", 0.98, "facialMatch", 0.94),
                VerificationSource.PROVIDER, "INCODE", "doc-ref-2", LocalDate.now().plusYears(5));

        assertThat(v.getProvider()).isEqualTo(KycProvider.INCODE);
        assertThat(v.getProviderReference()).isEqualTo("interview-abc123");
        assertThat(v.getVerificationMode()).isEqualTo(VerificationMode.AUTOMATIC);
        assertThat(v.getVerificationSource()).isEqualTo(VerificationSource.PROVIDER);
        // JSONB y no columnas: cada proveedor mide cosas distintas, y modelar la unión de todos
        // sería acoplarse a todos a la vez.
        assertThat(v.getProviderMetadata()).containsKeys("liveness", "facialMatch");
    }

    @Test
    @DisplayName("Escalado: el proveedor no alcanzó umbral y una persona resolvió encima")
    void escalado_se_distingue_del_automatico_limpio() {
        KycVerification v = build();

        v.verifyWithProvider(KycProvider.INCODE, "interview-xyz",
                Map.of("liveness", 0.51),
                VerificationSource.PROVIDER_ESCALATED, "ana.analista", "doc-3",
                LocalDate.now().plusYears(5));

        // Se intentó automático —eso es el MODO— y lo cerró una persona —eso es el ORIGEN—.
        // Colapsarlos perdería la única señal de que el proveedor se quedó corto.
        assertThat(v.getVerificationMode()).isEqualTo(VerificationMode.AUTOMATIC);
        assertThat(v.getVerificationSource()).isEqualTo(VerificationSource.PROVIDER_ESCALATED);
    }

    @Test
    @DisplayName("Un veredicto de proveedor sin decir cuál se rechaza")
    void sin_proveedor_no_hay_veredicto_de_proveedor() {
        assertThatThrownBy(() -> build().verifyWithProvider(
                KycProvider.NONE, "ref", Map.of(),
                VerificationSource.PROVIDER, "x", "doc", LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CUÁL proveedor");
    }

    @Test
    @DisplayName("Sin referencia de sesión tampoco: una verificación que no se puede reabrir no se puede defender")
    void sin_referencia_no_hay_veredicto_de_proveedor() {
        assertThatThrownBy(() -> build().verifyWithProvider(
                KycProvider.INCODE, "  ", Map.of(),
                VerificationSource.PROVIDER, "x", "doc", LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no se puede reabrir");
    }
}
