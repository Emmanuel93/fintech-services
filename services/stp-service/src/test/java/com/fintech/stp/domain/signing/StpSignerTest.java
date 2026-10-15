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

    private static KeyPair companyA;
    private static KeyPair companyB;

    @BeforeAll
    static void keys() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        companyA = generator.generateKeyPair();
        companyB = generator.generateKeyPair();
    }

    @Test
    @DisplayName("SG-T07 · SHA256withRSA es determinista: la misma cadena da el mismo sello")
    void deterministicSignature() {
        String first = StpSigner.sign(CADENA, companyA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        String second = StpSigner.sign(CADENA, companyA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("SG-T08 · el sello verifica, y alterar un byte lo invalida")
    void verifyRoundTrip() {
        String seal = StpSigner.sign(CADENA, companyA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);

        assertThat(StpSigner.verify(CADENA, seal, companyA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isTrue();
        assertThat(StpSigner.verify(CADENA.replace("100.00", "100.01"), seal, companyA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
    }

    @Test
    @DisplayName("SG-T08 · un sello corrupto devuelve false, no lanza — SO-04 es un resultado esperado")
    void corruptSignatureReturnsFalse() {
        assertThat(StpSigner.verify(CADENA, "no-es-base64!!", companyA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
        assertThat(StpSigner.verify(CADENA, null, companyA.getPublic(),
                SignatureAlgorithm.SHA256_WITH_RSA)).isFalse();
    }

    @Test
    @DisplayName("SG-T09 · aislamiento entre empresas: sellos distintos y verificación cruzada falla")
    void multiTenantIsolation() {
        String sealA = StpSigner.sign(CADENA, companyA.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        String sealB = StpSigner.sign(CADENA, companyB.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);

        assertThat(sealA).isNotEqualTo(sealB);
        assertThat(StpSigner.verify(CADENA, sealA, companyB.getPublic(),
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
