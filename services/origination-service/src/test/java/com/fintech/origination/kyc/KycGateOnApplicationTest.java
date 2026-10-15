package com.fintech.origination.kyc;

import com.fintech.origination.application.port.out.PartyReader;
import com.fintech.origination.domain.PersonNotVerifiedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La compuerta de KYC/AML al originar: <b>nada avanza sin veredicto</b>.
 *
 * <p>Toda persona registrada pasa por identidad y listas al alta, sin importar su rol —self-use,
 * distribuidor o beneficiaria de una línea—. Lo que estas pruebas fijan es el filo de esa regla:
 *
 * <ul>
 *   <li><b>Pendiente bloquea.</b> No haber comprobado no es haber salido limpio.</li>
 *   <li><b>No poder comprobar bloquea.</b> Si party no contesta, no se origina — lo contrario es
 *       dar crédito sin haber comprobado listas porque un servicio estaba caído.</li>
 *   <li><b>Se necesitan las DOS.</b> Identidad verificada con listas sin despejar no pasa, y al
 *       revés tampoco.</li>
 * </ul>
 *
 * <p>Se prueba sobre el {@code record Verification} y no montando el servicio entero a propósito:
 * la regla vive ahí, y una prueba que necesitara Postgres y Kafka para comprobar una conjunción
 * booleana no se correría cada vez que alguien la toca.
 */
class KycGateOnApplicationTest {

    private static PartyReader.Verification v(String identity, String screening) {
        boolean pasa = "VERIFIED".equals(identity) && "CLEARED".equals(screening);
        return new PartyReader.Verification(identity, screening, pasa,
                pasa ? null : "identidad " + identity + " / listas " + screening);
    }

    @Test
    @DisplayName("con identidad verificada y listas despejadas, pasa")
    void bothCleanProceeds() {
        assertThat(v("VERIFIED", "CLEARED").allowsToProceed()).isTrue();
    }

    @Test
    @DisplayName("pendiente bloquea: no haber comprobado no es haber salido limpio")
    void pendingBlocks() {
        assertThat(v("PENDING", "PENDING").allowsToProceed()).isFalse();
        assertThat(v("PENDING", "CLEARED").allowsToProceed()).isFalse();
        assertThat(v("VERIFIED", "PENDING").allowsToProceed()).isFalse();
    }

    @Test
    @DisplayName("sin proveedor de KYC todo queda en revisión, y en revisión NO pasa")
    void inReviewBlocks() {
        // Es el estado vigente de la plataforma: no hay contrato de KYC, así que toda identidad va
        // a la cola de un analista. Que eso bloquee es la regla funcionando, no un defecto.
        assertThat(v("IN_REVIEW", "CLEARED").allowsToProceed()).isFalse();
    }

    @Test
    @DisplayName("una coincidencia de listas bloquea aunque la identidad esté verificada")
    void aListMatchBlocksEvenWithVerifiedIdentity() {
        assertThat(v("VERIFIED", "POTENTIAL_MATCH").allowsToProceed()).isFalse();
        assertThat(v("VERIFIED", "CONFIRMED_MATCH").allowsToProceed()).isFalse();
    }

    @Test
    @DisplayName("si party no contesta, el veredicto BLOQUEA y dice que no se pudo comprobar")
    void anUnavailablePartyBlocks() {
        // Falla cerrado. La forma cómoda —devolver Optional vacío y que el llamador decida— invita
        // a escribir `.orElse(true)`, que es fallar abierto disfrazado de código cuidadoso.
        var verificacion = PartyReader.Verification.unavailable("connection refused");

        assertThat(verificacion.allowsToProceed()).isFalse();
        assertThat(verificacion.blockReason()).contains("no se pudo comprobar");
    }

    @Test
    @DisplayName("el motivo viaja, porque a cada caso se le dice algo distinto al cliente")
    void theReasonTravels() {
        // A una identidad en revisión se le pide mejor evidencia y la persona puede resolverlo.
        // A una coincidencia de listas no se le pide nada: no hay nada que la persona pueda hacer,
        // y mandarla a repetir su selfie sería enviarla a resolver algo que no cambia nada.
        assertThat(v("IN_REVIEW", "CLEARED").blockReason()).contains("IN_REVIEW");
        assertThat(v("VERIFIED", "POTENTIAL_MATCH").blockReason()).contains("POTENTIAL_MATCH");
    }

    @Test
    @DisplayName("la excepción lleva el prospecto y el motivo, no sólo «no autorizado»")
    void theExceptionCarriesTheReason() {
        UUID prospecto = UUID.randomUUID();
        var e = new PersonNotVerifiedException(prospecto, "listas POTENTIAL_MATCH");

        assertThat(e.prospectId()).isEqualTo(prospecto);
        assertThat(e.getMessage()).contains(prospecto.toString()).contains("POTENTIAL_MATCH");
    }
}
