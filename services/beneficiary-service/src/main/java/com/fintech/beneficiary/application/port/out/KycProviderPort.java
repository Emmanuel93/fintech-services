package com.fintech.beneficiary.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El proveedor externo de KYC (Incode, hoy sin contrato).
 *
 * <p>Es un puerto aparte de {@link IdentityVerificationGateway} a propósito: aquél es la
 * <b>política</b> —qué se hace con el resultado— y éste es la <b>integración</b>. Separarlos es lo
 * que permite probar la política de degradación sin un proveedor, y cambiar de proveedor sin tocar
 * la política.
 *
 * <p><b>Este puerto puede fallar y eso es normal.</b> Un proveedor de KYC se cae, tarda, o devuelve
 * basura; el contrato lo dice explícitamente con {@link Assessment#unavailable(String)} en vez de
 * dejar que una excepción se escape hacia arriba. Quien lo consume tiene que decidir qué hacer con
 * la indisponibilidad, no descubrirla.
 */
public interface KycProviderPort {

    /**
     * El resultado de pedirle al proveedor que evalúe una identidad.
     *
     * @param available    si el proveedor contestó. {@code false} = caído, sin contrato, o timeout.
     * @param scores       lo que midió, por métrica ({@code facialMatch}, {@code liveness}, …).
     *                     Vacío cuando no está disponible.
     * @param failedChecks documentos o validaciones que el proveedor no pudo resolver.
     * @param detail       para el log y para que el analista sepa qué pasó.
     */
    record Assessment(boolean available,
                      Map<String, Double> scores,
                      List<String> failedChecks,
                      String detail) {

        public static Assessment unavailable(String detail) {
            return new Assessment(false, Map.of(), List.of(), detail);
        }

        public static Assessment of(Map<String, Double> scores, List<String> failedChecks) {
            return new Assessment(true, Map.copyOf(scores), List.copyOf(failedChecks), null);
        }
    }

    /**
     * Evalúa la identidad de una beneficiaria.
     *
     * <p>No lanza: una caída del proveedor se reporta como {@link Assessment#unavailable}. Dejar
     * que la excepción viaje obligaría a cada llamador a acordarse de atraparla, y el día que
     * alguien se olvide, el proveedor caído tumbaría una colocación.
     */
    Assessment assess(UUID placementId, UUID prospectId);

    /** Nombre del proveedor, para el rastro del expediente. */
    String providerName();
}
