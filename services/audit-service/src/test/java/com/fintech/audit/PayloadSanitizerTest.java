package com.fintech.audit;

import com.fintech.audit.application.PayloadSanitizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadSanitizerTest {

    @Test
    void redactsTopLevelSecrets_keepsNonSensitive() {
        String out = PayloadSanitizer.sanitize(
                "{\"username\":\"juanp\",\"password\":\"s3cr3t\",\"otp\":\"123456\"}");

        assertThat(out).contains("juanp");                 // username no es secreto
        assertThat(out).doesNotContain("s3cr3t");
        assertThat(out).doesNotContain("123456");
        assertThat(out).contains("***REDACTED***");
    }

    @Test
    void redactsNestedSecrets() {
        String out = PayloadSanitizer.sanitize(
                "{\"user\":{\"name\":\"ana\",\"secret\":\"abc\"},\"outcome\":\"SUCCESS\"}");

        assertThat(out).contains("ana");
        assertThat(out).contains("SUCCESS");
        assertThat(out).doesNotContain("abc");
        assertThat(out).contains("***REDACTED***");
    }

    @Test
    void redactsSecretsInsideArrays() {
        String out = PayloadSanitizer.sanitize(
                "{\"credentials\":[{\"apiKey\":\"k1\"},{\"apiKey\":\"k2\"}]}");

        assertThat(out).doesNotContain("k1").doesNotContain("k2");
        assertThat(out).contains("***REDACTED***");
    }

    @Test
    void preservesNonSecretIdentifiers_likeSessionTokenId() {
        // "token" a secas NO está en la lista negra: un JTI/id de sesión es identificador, no secreto.
        String out = PayloadSanitizer.sanitize(
                "{\"sessionTokenId\":\"jti-abc-123\",\"outcome\":\"SUCCESS\"}");

        assertThat(out).contains("jti-abc-123");
        assertThat(out).doesNotContain("***REDACTED***");
    }

    @Test
    void nonJsonPayload_returnedUnchanged() {
        String out = PayloadSanitizer.sanitize("no soy json");
        assertThat(out).isEqualTo("no soy json");
    }

    @Test
    void nullOrBlank_returnedAsIs() {
        assertThat(PayloadSanitizer.sanitize(null)).isNull();
        assertThat(PayloadSanitizer.sanitize("")).isEmpty();
    }
}
