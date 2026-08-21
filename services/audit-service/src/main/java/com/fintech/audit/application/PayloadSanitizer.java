package com.fintech.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redacta campos sensibles del payload antes de persistirlo en la bitácora.
 *
 * <p>El log de auditoría es inmutable y de larga retención: un secreto que entra ahí, ahí se queda.
 * Y entran de verdad — p.ej. {@code origination.prospect-created} viaja con el password en claro para
 * el aprovisionamiento en identity. Aquí se reemplaza el valor de cualquier campo cuyo nombre delate
 * un secreto por {@code ***REDACTED***}, a cualquier profundidad del JSON. Si el payload no es JSON
 * válido se guarda tal cual (ya es solo texto).
 */
public final class PayloadSanitizer {

    private static final String REDACTED = "***REDACTED***";

    /** Fragmentos que, si aparecen en el nombre de un campo (minúsculas), marcan el valor como secreto. */
    private static final Set<String> SENSITIVE_FRAGMENTS = Set.of(
            "password", "passwd", "pwd", "secret", "otp", "totp", "cvv", "cvc", "pin",
            "privatekey", "apikey", "clientsecret", "accesstoken", "refreshtoken", "mfacode");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PayloadSanitizer() {}

    public static String sanitize(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            redact(root);
            return MAPPER.writeValueAsString(root);
        } catch (Exception ex) {
            // No es JSON: nada estructurado que redactar; se guarda como vino.
            return payload;
        }
    }

    private static void redact(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> names = new ArrayList<>();
            obj.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (isSensitive(name)) {
                    obj.put(name, REDACTED);
                } else {
                    redact(obj.get(name));
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(PayloadSanitizer::redact);
        }
    }

    private static boolean isSensitive(String fieldName) {
        String lower = fieldName.toLowerCase();
        return SENSITIVE_FRAGMENTS.stream().anyMatch(lower::contains);
    }
}
