package com.fintech.stp.domain.signing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SG-T07..SG-T11 — firma, verificación y aislamiento entre empresas. */
class StpSignerTest {

    private static final String CADENA = "||846|empresa|20230516|clave|90646|100.00||";

    private static KeyPair empresaA;
    private static KeyPair empresaB;

    @BeforeAll
    static void keys() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        empresaA = generator.generateKeyPair();
        empresaB = generator.generateKeyPair();
    }

    @Test
    @DisplayName("SG-T07 · SHA256withRSA es determinista: la misma cadena da el mismo sello")
    void deterministicSignature() {
        String first = StpSigner.sign(CADENA, empresaA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        String second = StpSigner.sign(CADENA, empresaA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("SG-T08 · el sello verifica, y alterar un byte lo invalida")
    void verifyRoundTrip() {
        String sello = StpSigner.sign(CADENA, empresaA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);

        assertThat(StpSigner.verify(CADENA, sello, empresaA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isTrue();
        assertThat(StpSigner.verify(CADENA.replace("100.00", "100.01"), sello, empresaA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
    }

    @Test
    @DisplayName("SG-T08 · un sello corrupto devuelve false, no lanza — SO-04 es un resultado esperado")
    void corruptSignatureReturnsFalse() {
        assertThat(StpSigner.verify(CADENA, "no-es-base64!!", empresaA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
        assertThat(StpSigner.verify(CADENA, null, empresaA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
    }

    @Test
    @DisplayName("SG-T09 · aislamiento entre empresas: sellos distintos y verificación cruzada falla")
    void multiTenantIsolation() {
        String selloA = StpSigner.sign(CADENA, empresaA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        String selloB = StpSigner.sign(CADENA, empresaB.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);

        assertThat(selloA).isNotEqualTo(selloB);
        assertThat(StpSigner.verify(CADENA, selloA, empresaB.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
    }

    @Test
    @DisplayName("SG-T11 · sin llave falla, y el mensaje no filtra la cadena original")
    void failsWithoutKeyWithoutLeaking() {
        assertThatThrownBy(() -> StpSigner.sign(CADENA, null, SignatureAlgorithm.SHA256_WITH_RSA))
                .isInstanceOf(StpSignatureException.class)
                .hasMessageNotContaining("846")
                .hasMessageNotContaining("100.00");
    }
}
