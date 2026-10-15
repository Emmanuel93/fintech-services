package com.fintech.beneficiary.screening;

import com.fintech.beneficiary.domain.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La otra mitad de la regla de colocación: <b>no estar en listas</b>.
 *
 * <p>«La decisión es del distribuidor <b>si y sólo si</b> se pudo validar la identidad <b>y</b> no se
 * encuentra en listas negras.» Hasta ahora {@code approve} comprobaba la primera mitad y nada
 * comprobaba la segunda, así que una beneficiaria sancionada con su INE perfectamente cotejada se
 * aprobaba y se le depositaba.
 *
 * <p>Son dos comprobaciones de preguntas distintas y ninguna sustituye a la otra: eso es lo que estas
 * pruebas fijan, porque es lo que se rompió una vez y se puede volver a romper.
 */
class PlacementRequiresCleanListsTest {

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID LINE        = UUID.randomUUID();
    private static final UUID PROSPECT    = UUID.randomUUID();
    private static final UUID PARTY       = UUID.randomUUID();
    private static final String CLABE     = "012180012345678905";

    private static final ScreeningVerdict CLEARED_VERDICT = new ScreeningVerdict("CLEARED", null);

    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);

    /** Una colocación lista para aprobar: expediente completo e identidad comprobada. */
    private static Placement readyForApproval() {
        Placement p = Placement.draft(
                DISTRIBUTOR, LINE, "DISTRIBUIDORA-QUINCENAL", "María Luisa Cortés Hernández",
                "5541829037", "Clienta de mi tienda desde 2023",
                new BigDecimal("18000"), 12, new BigDecimal("1800"),
                VerificationMode.SELF_SERVICE_LINK, Instant.now().plusSeconds(86_400),
                LIMITS,
                new BigDecimal("205000"));
        p.startKyc();
        p.completeKyc(PROSPECT, PARTY, CLABE);
        p.bureauReady();
        p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL,
                "analista-1", null);
        return p;
    }

    // ── Lo que bloquea ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("una coincidencia CONFIRMADA de listas no se aprueba, por verificada que esté la identidad")
    void aconfirmedMatchBlocksApproval() {
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true,
                new ScreeningVerdict("CONFIRMED_MATCH", "coincide con OFAC-SDN")))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);

        // Y no avanza: el estado se queda donde estaba, no en un limbo.
        assertThat(p.getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);
    }

    @Test
    @DisplayName("una coincidencia POSIBLE también bloquea: la despeja cumplimiento, no la distribuidora")
    void apotentialMatchAlsoBlocks() {
        // Es el caso común —la homonimia— y es justo el que no puede resolver quien coloca: la
        // distribuidora no tiene cómo saber si su clienta es la persona de la lista.
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true,
                new ScreeningVerdict("POTENTIAL_MATCH", "mismo nombre y fecha de nacimiento")))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);
    }

    @Test
    @DisplayName("PENDIENTE bloquea: no haber cruzado no es haber salido limpio")
    void pendingBlocks() {
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true, new ScreeningVerdict("PENDING", "sin cruzar")))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);
    }

    @Test
    @DisplayName("sin veredicto —porque no se pudo consultar— también bloquea, y es lo correcto")
    void anunavailableVerdictBlocks() {
        // Fallar abierto aquí significaría depositar sin haber mirado listas porque un servicio
        // estaba caído. La polaridad correcta es la incómoda.
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true, ScreeningVerdict.unavailable("party no contestó")))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);
    }

    @Test
    @DisplayName("un veredicto NULO bloquea: llamar sin consultar no puede ser la vía fácil")
    void anullVerdictBlocks() {
        // Protege contra el llamador que añada una ruta nueva y no pase el veredicto. Sin esto, la
        // forma de saltarse el control sería olvidarse de él, que es la más probable de todas.
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true, null))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);
    }

    @Test
    @DisplayName("un estado de listas desconocido bloquea, en vez de pasar por no estar en la lista de malos")
    void anunknownStatusBlocks() {
        // La compuerta está escrita en positivo —sólo CLEARED pasa— precisamente para esto: el día
        // que cumplimiento añada un estado nuevo, lo nuevo se detiene y alguien lo decide.
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true, new ScreeningVerdict("EN_REVISION_REFORZADA", null)))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);
    }

    // ── Lo que deja pasar, y en qué orden se explican los motivos ────────────────────────────

    @Test
    @DisplayName("identidad comprobada y listas despejadas: eso sí aprueba")
    void verifiedAndClearedApproves() {
        Placement p = readyForApproval();

        p.approve(true, CLEARED_VERDICT);

        assertThat(p.getStatus()).isEqualTo(PlacementStatus.APPROVED);
    }

    @Test
    @DisplayName("si faltan las dos, el motivo es el de identidad — el que la distribuidora puede ayudar a resolver")
    void identityIsReportedFirstWhenBothFail() {
        // No es cosmético: a una identidad en revisión se le pide mejor evidencia y la distribuidora
        // puede conseguirla. Ante una coincidencia de listas no hay nada que ella pueda hacer, así
        // que decirle eso primero la mandaría a resolver lo único que no tiene arreglo.
        Placement p = Placement.draft(
                DISTRIBUTOR, LINE, "DISTRIBUIDORA-QUINCENAL", "María Luisa Cortés Hernández",
                "5541829037", null, new BigDecimal("18000"), 12, new BigDecimal("1800"),
                VerificationMode.SELF_SERVICE_LINK, Instant.now().plusSeconds(86_400),
                LIMITS,
                new BigDecimal("205000"));
        p.startKyc();
        p.completeKyc(PROSPECT, PARTY, CLABE);
        p.bureauReady();

        assertThatThrownBy(() -> p.approve(true, new ScreeningVerdict("CONFIRMED_MATCH", "OFAC")))
                .isInstanceOf(IdentityNotVerifiedException.class);
    }

    @Test
    @DisplayName("el riesgo firmado no sustituye al veredicto de listas")
    void theriskAcknowledgementDoesNotReplaceScreening() {
        // Son de responsables distintos: la distribuidora asume el riesgo COMERCIAL; que la persona
        // esté en una lista de sancionados no es un riesgo que ella pueda asumir por nadie.
        Placement p = readyForApproval();

        assertThatThrownBy(() -> p.approve(true, new ScreeningVerdict("CONFIRMED_MATCH", "OFAC")))
                .isInstanceOf(BeneficiaryScreeningBlockedException.class);
    }

    // ── El veredicto como valor ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("el veredicto no consultable dice POR QUÉ no se pudo, no sólo que no")
    void anunavailableVerdictCarriesItsReason() {
        ScreeningVerdict v = ScreeningVerdict.unavailable("party devolvió 503");

        assertThat(v.allowsPlacement()).isFalse();
        assertThat(v.status()).isEqualTo(ScreeningVerdict.UNKNOWN);
        assertThat(v.blockReason()).contains("503");
    }

    @Test
    @DisplayName("el mensaje del bloqueo nombra la colocación, el estado y a quién le toca resolverlo")
    void theblockMessageSaysWhoResolvesIt() {
        // Quien lee el error tiene que saber qué hacer. «No se puede aprobar» a secas manda a la
        // distribuidora a llamar a soporte, y soporte tampoco puede hacer nada.
        UUID id = UUID.randomUUID();
        var e = new BeneficiaryScreeningBlockedException(id,
                new ScreeningVerdict("POTENTIAL_MATCH", "coincide por nombre y fecha"));

        assertThat(e.getMessage()).contains(id.toString())
                                  .contains("POTENTIAL_MATCH")
                                  .contains("coincide por nombre y fecha")
                                  .contains("cumplimiento");
        assertThat(e.getErrorCode()).isEqualTo("BENEFICIARY_SCREENING_BLOCKED");
    }
}
