package com.fintech.beneficiary.application.port.out;

import com.fintech.beneficiary.domain.IdentityDecision;
import com.fintech.beneficiary.domain.VerificationSource;

import java.util.List;

/**
 * Decide qué se hace con la evidencia de identidad: resolverla sola o mandarla a una persona.
 *
 * <p><b>La revisión manual es infraestructura permanente, no un modo temporal.</b> Aun con
 * proveedor contratado, el analista recibe lo que no alcanzó umbral, lo que el proveedor no pudo
 * validar y lo que no pudo evaluar porque estaba caído. Por eso la pregunta de este puerto no es
 * «¿manual o automático?» sino «¿esto se resuelve solo?» — y la cola del analista existe siempre.
 */
public interface IdentityVerificationGateway {

    /**
     * El resultado de mirar la evidencia.
     *
     * @param decision {@code PENDING} significa «que lo vea una persona». No es un error: es el
     *                 resultado normal de un caso dudoso y el único posible en modo manual.
     * @param source   quién emitió el juicio. Nulo cuando va a revisión humana — lo llenará quien
     *                 firme.
     * @param reasons  <b>por qué</b>. Es lo que el analista lee antes de abrir el expediente:
     *                 «facial 0.82 &lt; 0.90», «proveedor no disponible», «INE ilegible». Sin esto la
     *                 cola sería una lista de casos sin pista de qué mirar.
     */
    record VerificationOutcome(IdentityDecision decision, VerificationSource source, List<String> reasons) {

        public static VerificationOutcome requiresHumanReview(String... reasons) {
            return new VerificationOutcome(IdentityDecision.PENDING, null, List.of(reasons));
        }

        public static VerificationOutcome autoVerified(String... reasons) {
            return new VerificationOutcome(IdentityDecision.VERIFIED, VerificationSource.PROVIDER, List.of(reasons));
        }

        public boolean requiresHumanReview() {
            return decision == IdentityDecision.PENDING;
        }

        /** Las razones en una línea, para guardarlas junto a la colocación. */
        public String reasonSummary() {
            return reasons == null || reasons.isEmpty() ? null : String.join(" · ", reasons);
        }
    }

    VerificationOutcome evaluate(java.util.UUID placementId, java.util.UUID prospectId);

    /** Cómo se está verificando ahora, para que la consola lo diga en vez de que se adivine. */
    String describeStrategy();
}
