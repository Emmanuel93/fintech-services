package com.fintech.beneficiary.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La máquina de estados de la colocación, arista por arista.
 *
 * <p>Se prueba de forma <b>exhaustiva y no por ejemplos</b>: de los 132 pares (origen, destino)
 * posibles, los 16 que el dominio declara válidos se ejercen y los 116 restantes se verifican como
 * rechazados. Un test por caso feliz dejaría pasar precisamente el bug que importa aquí —una arista
 * de más—, que es como una colocación vencida termina aprobada.
 */
class PlacementStateMachineTest {

    private static final UUID DISTRIBUTOR = UUID.randomUUID();
    private static final UUID LINE        = UUID.randomUUID();
    private static final UUID PROSPECT    = UUID.randomUUID();
    private static final UUID PARTY       = UUID.randomUUID();
    private static final UUID DISPOSITION = UUID.randomUUID();
    private static final String CLABE     = "032180000118399999";
    /** $18,000 a 24 quincenas al 28.9 % anual — el número del contrato de la app. */
    private static final BigDecimal PAYMENT = new BigDecimal("868.06");
    private static final Instant EXPIRY     = Instant.now().plus(7, ChronoUnit.DAYS);
    /** Como los siembra `015-distributor-placement-limits.sql` para DL-DIST-STD-V1. */
    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);


    private static Placement draft() {
        return Placement.draft(DISTRIBUTOR, LINE, "María Luisa Cortés Hernández", "5541829037",
                "Clienta de mi tienda desde 2023", new BigDecimal("18000"), 12, PAYMENT,
                VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null);
    }

    /** Lleva una colocación recién creada hasta el estado pedido por el camino canónico. */
    private static Placement at(PlacementStatus target) {
        Placement p = draft();
        if (target == PlacementStatus.INVITED) return p;

        if (target == PlacementStatus.EXPIRED)   { p.expire();          return p; }
        if (target == PlacementStatus.CANCELLED) { p.cancel("revocada"); return p; }

        p.startKyc();
        if (target == PlacementStatus.KYC_IN_PROGRESS) return p;
        if (target == PlacementStatus.FAILED) { p.fail("prueba de vida fallida"); return p; }

        p.completeKyc(PROSPECT, PARTY, CLABE);
        if (target == PlacementStatus.KYC_COMPLETED) return p;

        p.bureauReady();
        if (target == PlacementStatus.BUREAU_READY) return p;
        if (target == PlacementStatus.REJECTED) { p.reject("no la conozco tanto"); return p; }

        // Aprobar exige identidad comprobada: es la regla nueva y el camino canónico la respeta.
        p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
        p.approve(true);
        if (target == PlacementStatus.APPROVED) return p;

        p.markDisbursing(DISPOSITION);
        if (target == PlacementStatus.DISBURSING) return p;

        p.markDisbursed();
        if (target == PlacementStatus.DISBURSED) return p;

        p.markPaidOff();
        return p;
    }

    /** Ejerce la transición en el agregado, o lanza si la arista no existe. */
    private static void attempt(Placement p, PlacementStatus target) {
        switch (target) {
            case KYC_IN_PROGRESS -> p.startKyc();
            case KYC_COMPLETED   -> p.completeKyc(PROSPECT, PARTY, CLABE);
            case BUREAU_READY    -> p.bureauReady();
            case APPROVED        -> {
                // El helper de aristas prueba la transición, no la identidad: se deja verificada
                // para que lo que falle sea la arista y no una precondición de otro eje.
                if (p.getIdentityDecision() != IdentityDecision.VERIFIED && p.hasFile()) {
                    p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
                }
                p.approve(true);
            }
            case DISBURSING      -> p.markDisbursing(DISPOSITION);
            case DISBURSED       -> p.markDisbursed();
            case PAID_OFF        -> p.markPaidOff();
            case REJECTED        -> p.reject("no la conozco tanto");
            case EXPIRED         -> p.expire();
            case CANCELLED       -> p.cancel("revocada");
            case FAILED          -> p.fail("algo se rompió");
            case INVITED         -> throw new IllegalArgumentException("INVITED no es destino de ninguna arista");
        }
    }

    static List<Object[]> allEdges() {
        List<Object[]> edges = new ArrayList<>();
        for (PlacementStatus from : PlacementStatus.values()) {
            for (PlacementStatus to : PlacementStatus.values()) {
                if (to == PlacementStatus.INVITED) continue; // no hay arista de entrada a INVITED
                edges.add(new Object[]{from, to});
            }
        }
        return edges;
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("allEdges")
    @DisplayName("cada par (origen, destino) se comporta como lo declara la máquina")
    void everyEdgeBehavesAsDeclared(PlacementStatus from, PlacementStatus to) {
        Placement p = at(from);
        assertThat(p.getStatus()).as("el helper dejó la colocación en el estado equivocado").isEqualTo(from);

        if (from.canTransitionTo(to)) {
            attempt(p, to);
            assertThat(p.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> attempt(p, to))
                    .isInstanceOf(InvalidPlacementTransitionException.class);
            assertThat(p.getStatus()).as("una transición rechazada no debe mover el estado").isEqualTo(from);
        }
    }

    @Test
    @DisplayName("la máquina declara exactamente 16 aristas — si cambia, es una decisión, no un descuido")
    void edgeCountIsPinned() {
        long edges = java.util.Arrays.stream(PlacementStatus.values())
                .mapToLong(s -> s.allowedTargets().size()).sum();
        assertThat(edges).isEqualTo(16);
    }

    @ParameterizedTest
    @EnumSource(value = PlacementStatus.class,
            names = {"PAID_OFF", "REJECTED", "EXPIRED", "CANCELLED", "FAILED"})
    @DisplayName("los cinco estados terminales no tienen salida")
    void terminalStatesHaveNoExit(PlacementStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedTargets()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PlacementStatus.class,
            names = {"INVITED", "KYC_IN_PROGRESS", "KYC_COMPLETED", "BUREAU_READY",
                     "APPROVED", "DISBURSING", "DISBURSED"})
    @DisplayName("ningún estado vivo es terminal: todos tienen a dónde ir")
    void liveStatesAlwaysHaveSomewhereToGo(PlacementStatus live) {
        assertThat(live.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("desembolsar no es el final: entre el depósito y el último pago hay vida que representar")
    void disbursedIsNotTheEnd() {
        assertThat(PlacementStatus.DISBURSED.isTerminal()).isFalse();
        assertThat(PlacementStatus.DISBURSED.allowedTargets())
                .containsExactly(PlacementStatus.PAID_OFF);
    }

    @Nested
    @DisplayName("los límites del producto — tope por beneficiario, mínimo, plazos y escalones")
    class ProductLimits {

        private static Placement withAmountAndTerm(BigDecimal amount, int term, BigDecimal availableLine) {
            return Placement.draft(DISTRIBUTOR, LINE, "María Luisa Cortés Hernández", "5541829037",
                    null, amount, term, PAYMENT, VerificationMode.SELF_SERVICE_LINK,
                    EXPIRY, LIMITS, availableLine);
        }

        @Test
        @DisplayName("el tope por beneficiario se aplica aunque la línea alcance de sobra")
        void perBeneficiaryCapAppliesEvenWithPlentyOfLine() {
            // Línea de $500,000 disponibles, tope del producto $60,000. Sin este límite, toda la
            // línea podría quedar colgada del historial de una sola persona.
            assertThatThrownBy(() -> withAmountAndTerm(
                    new BigDecimal("61000"), 12, new BigDecimal("500000")))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("El máximo que puedes colocarle a una persona es 60000");

            assertThat(withAmountAndTerm(new BigDecimal("60000"), 12, new BigDecimal("500000"))
                    .getAmount()).isEqualByComparingTo("60000");
        }

        @Test
        @DisplayName("la línea disponible se revisa además del tope, y son cosas distintas")
        void availableLineIsCheckedOnTopOfTheCap() {
            // Dentro del tope del producto pero fuera de lo que a él le queda.
            assertThatThrownBy(() -> withAmountAndTerm(
                    new BigDecimal("50000"), 12, new BigDecimal("20000")))
                    .isInstanceOf(InsufficientLineException.class);

            // Sin línea conocida no se revisa cupo: la autoridad es credit-portfolio al disponer.
            assertThat(withAmountAndTerm(new BigDecimal("50000"), 12, null).getAmount())
                    .isEqualByComparingTo("50000");
        }

        @Test
        @DisplayName("el piso y el escalón del monto también son del producto")
        void amountFloorAndStepComeFromTheProduct() {
            assertThatThrownBy(() -> withAmountAndTerm(new BigDecimal("4000"), 12, null))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("mínimo");

            // El diseño mueve el slider de mil en mil; aceptar $18,500 sería cotizar otro producto.
            assertThatThrownBy(() -> withAmountAndTerm(new BigDecimal("18500"), 12, null))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("múltiplo de 1000");
        }

        @Test
        @DisplayName("el plazo vive dentro del rango configurado, no dentro de una constante")
        void termMustFallInsideTheConfiguredRange() {
            assertThatThrownBy(() -> withAmountAndTerm(new BigDecimal("18000"), 7, null))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("entre 8 y 16 quincenas");

            assertThatThrownBy(() -> withAmountAndTerm(new BigDecimal("18000"), 17, null))
                    .isInstanceOf(PlacementValidationException.class);

            assertThat(withAmountAndTerm(new BigDecimal("18000"), 8, null).getTermFortnights()).isEqualTo(8);
            assertThat(withAmountAndTerm(new BigDecimal("18000"), 16, null).getTermFortnights()).isEqualTo(16);
        }

        @Test
        @DisplayName("el escalón de plazo expresa steppers que un rango solo no puede")
        void termStepExpressesWhatARangeCannot() {
            // 12/24/36/48 — el stepper del diseño, escalón de 12.
            PlacementLimits everyTwelve = new PlacementLimits(
                    new BigDecimal("5000"), new BigDecimal("60000"), 1000, 12, 48, 12);

            everyTwelve.validateTerm(12);
            everyTwelve.validateTerm(24);
            everyTwelve.validateTerm(48);

            assertThatThrownBy(() -> everyTwelve.validateTerm(18))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("de 12 en 12");
        }

        @Test
        @DisplayName("un rango invertido revienta al configurarlo, no al colocar")
        void anInvertedRangeFailsAtConfigurationTime() {
            assertThatThrownBy(() -> new PlacementLimits(
                    new BigDecimal("60000"), new BigDecimal("5000"), 1000, 8, 16, 1))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("Rango de monto inválido");

            assertThatThrownBy(() -> new PlacementLimits(
                    new BigDecimal("5000"), new BigDecimal("60000"), 1000, 16, 8, 1))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("Rango de plazo inválido");

            assertThatThrownBy(() -> new PlacementLimits(null, new BigDecimal("60000"), 1000, 8, 16, 1))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("no tiene configurado el rango");
        }
    }

    @Nested
    @DisplayName("proyección a los estados que la app parsea")
    class WireProjection {

        /** Los nueve valores exactos de `PlacementStatus.fromName` más `failed`. */
        private static final List<String> APP_VALUES = List.of(
                "invited", "kycInProgress", "bureauReady", "approved", "active",
                "paidOff", "declined", "expired", "cancelled", "failed");

        @ParameterizedTest
        @EnumSource(PlacementStatus.class)
        @DisplayName("todo estado interno proyecta a un valor que la app conoce")
        void everyStatusProjectsToAKnownValue(PlacementStatus status) {
            assertThat(APP_VALUES).contains(status.wireName());
        }

        @Test
        @DisplayName("el mapeo completo, fijado — la app cae a `invited` ante un valor que no conoce")
        void theProjectionIsPinned() {
            assertThat(PlacementStatus.INVITED.wireName()).isEqualTo("invited");
            assertThat(PlacementStatus.KYC_IN_PROGRESS.wireName()).isEqualTo("kycInProgress");
            assertThat(PlacementStatus.KYC_COMPLETED.wireName()).isEqualTo("kycInProgress");
            assertThat(PlacementStatus.BUREAU_READY.wireName()).isEqualTo("bureauReady");
            assertThat(PlacementStatus.APPROVED.wireName()).isEqualTo("approved");
            assertThat(PlacementStatus.DISBURSING.wireName()).isEqualTo("approved");
            assertThat(PlacementStatus.DISBURSED.wireName()).isEqualTo("active");
            assertThat(PlacementStatus.PAID_OFF.wireName()).isEqualTo("paidOff");
            assertThat(PlacementStatus.REJECTED.wireName()).isEqualTo("declined");
            assertThat(PlacementStatus.EXPIRED.wireName()).isEqualTo("expired");
            assertThat(PlacementStatus.CANCELLED.wireName()).isEqualTo("cancelled");
            assertThat(PlacementStatus.FAILED.wireName()).isEqualTo("failed");
        }

        @Test
        @DisplayName("una colocación muerta nunca se proyecta como una viva")
        void deadPlacementsNeverLookAlive() {
            // Es el riesgo concreto del fallback de la app: pintar como «liga enviada, esperando»
            // algo que ya murió haría que el distribuidor esperara para siempre.
            for (PlacementStatus s : PlacementStatus.values()) {
                if (s.isTerminal()) {
                    assertThat(s.wireName()).as("%s", s).isNotEqualTo("invited");
                }
            }
        }
    }

    @Nested
    @DisplayName("las dos reglas del diseño que la máquina graba")
    class DesignRules {

        @Test
        @DisplayName("revocar sólo cabe antes de que exista expediente")
        void cancelOnlyBeforeTheFileExists() {
            assertThat(PlacementStatus.INVITED.canTransitionTo(PlacementStatus.CANCELLED)).isTrue();
            assertThat(PlacementStatus.KYC_IN_PROGRESS.canTransitionTo(PlacementStatus.CANCELLED)).isTrue();

            // Con expediente, la salida del distribuidor es REJECTED: una decisión con nombre.
            assertThat(PlacementStatus.KYC_COMPLETED.canTransitionTo(PlacementStatus.CANCELLED)).isFalse();
            assertThat(PlacementStatus.BUREAU_READY.canTransitionTo(PlacementStatus.CANCELLED)).isFalse();
        }

        @Test
        @DisplayName("vencer sólo cabe mientras la liga vive: los 7 días corren sobre la liga, no sobre la colocación")
        void expiryOnlyWhileTheInviteIsLive() {
            assertThat(PlacementStatus.INVITED.canTransitionTo(PlacementStatus.EXPIRED)).isTrue();
            assertThat(PlacementStatus.KYC_IN_PROGRESS.canTransitionTo(PlacementStatus.EXPIRED)).isTrue();

            assertThat(PlacementStatus.KYC_COMPLETED.canTransitionTo(PlacementStatus.EXPIRED)).isFalse();
            assertThat(PlacementStatus.BUREAU_READY.canTransitionTo(PlacementStatus.EXPIRED)).isFalse();
            assertThat(PlacementStatus.APPROVED.canTransitionTo(PlacementStatus.EXPIRED)).isFalse();
        }

        @Test
        @DisplayName("inviteIsLive coincide con quién puede vencer")
        void inviteLivenessMatchesExpiry() {
            for (PlacementStatus s : PlacementStatus.values()) {
                assertThat(s.inviteIsLive())
                        .as("%s", s)
                        .isEqualTo(s.canTransitionTo(PlacementStatus.EXPIRED));
            }
        }

        @Test
        @DisplayName("la línea sólo queda comprometida al disponer, nunca al invitar")
        void lineIsOnlyConsumedOnDisbursement() {
            assertThat(PlacementStatus.INVITED.consumesLine()).isFalse();
            assertThat(PlacementStatus.KYC_COMPLETED.consumesLine()).isFalse();
            assertThat(PlacementStatus.BUREAU_READY.consumesLine()).isFalse();
            // Aprobar decide; lo que compromete la línea es la disposición.
            assertThat(PlacementStatus.APPROVED.consumesLine()).isFalse();
            assertThat(PlacementStatus.DISBURSING.consumesLine()).isTrue();
            assertThat(PlacementStatus.DISBURSED.consumesLine()).isTrue();
            assertThat(PlacementStatus.PAID_OFF.consumesLine()).isTrue();
        }

        @Test
        @DisplayName("reenviar reinicia los 7 días, y sólo mientras la liga viva")
        void resendRestartsTheWindow() {
            Placement p = at(PlacementStatus.INVITED);
            Instant original = p.getInviteExpiresAt();
            Instant renewed  = original.plus(7, ChronoUnit.DAYS);

            p.restartInviteWindow(renewed);
            assertThat(p.getInviteExpiresAt()).isEqualTo(renewed);

            // Con expediente ya no hay liga que reenviar.
            Placement withFile = at(PlacementStatus.BUREAU_READY);
            assertThatThrownBy(() -> withFile.restartInviteWindow(renewed))
                    .isInstanceOf(InvalidPlacementTransitionException.class);
        }

        @Test
        @DisplayName("la liga vencida sólo lo está mientras podía vencer")
        void expiryOnlyAppliesToLiveInvites() {
            Placement live = at(PlacementStatus.INVITED);
            assertThat(live.inviteHasExpired(Instant.now())).isFalse();
            assertThat(live.inviteHasExpired(live.getInviteExpiresAt().plusSeconds(1))).isTrue();

            // Con el KYC cerrado la ventana deja de aplicar aunque la fecha ya haya pasado.
            Placement completed = at(PlacementStatus.KYC_COMPLETED);
            assertThat(completed.inviteHasExpired(Instant.now().plus(365, ChronoUnit.DAYS))).isFalse();
        }
    }

    @Nested
    @DisplayName("invariantes de las transiciones que llevan datos")
    class TransitionInvariants {

        @Test
        @DisplayName("aprobar sin aceptación de riesgo no es una aprobación incompleta: no existe")
        void approveWithoutRiskAcknowledgementIsRejected() {
            Placement p = at(PlacementStatus.BUREAU_READY);

            assertThatThrownBy(() -> p.approve(false))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("aceptación expresa del riesgo");

            assertThat(p.getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);
            assertThat(p.getDecidedAt()).isNull();
        }

        @Test
        @DisplayName("cerrar el KYC exige prospecto, Party y CLABE de 18 dígitos")
        void completingKycRequiresTheWholeFile() {
            assertThatThrownBy(() -> at(PlacementStatus.KYC_IN_PROGRESS).completeKyc(null, PARTY, CLABE))
                    .isInstanceOf(PlacementValidationException.class);

            assertThatThrownBy(() -> at(PlacementStatus.KYC_IN_PROGRESS).completeKyc(PROSPECT, null, CLABE))
                    .isInstanceOf(PlacementValidationException.class);

            assertThatThrownBy(() -> at(PlacementStatus.KYC_IN_PROGRESS).completeKyc(PROSPECT, PARTY, "123"))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("18 dígitos");

            assertThatThrownBy(() -> at(PlacementStatus.KYC_IN_PROGRESS).completeKyc(PROSPECT, PARTY, null))
                    .isInstanceOf(PlacementValidationException.class);
        }

        @Test
        @DisplayName("el expediente queda completo al cerrar el KYC")
        void fileIsPopulatedOnKycCompletion() {
            Placement p = at(PlacementStatus.KYC_IN_PROGRESS);
            assertThat(p.hasFile()).isFalse();

            p.completeKyc(PROSPECT, PARTY, CLABE);

            assertThat(p.hasFile()).isTrue();
            assertThat(p.getBeneficiaryProspectId()).isEqualTo(PROSPECT);
            assertThat(p.getBeneficiaryPartyId()).isEqualTo(PARTY);
            assertThat(p.getBeneficiaryClabe()).isEqualTo(CLABE);
        }

        @Test
        @DisplayName("fallar siempre lleva motivo")
        void failureAlwaysCarriesAReason() {
            assertThatThrownBy(() -> at(PlacementStatus.KYC_IN_PROGRESS).fail(null))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("motivo");

            assertThatThrownBy(() -> at(PlacementStatus.KYC_IN_PROGRESS).fail("  "))
                    .isInstanceOf(PlacementValidationException.class);
        }

        @Test
        @DisplayName("no se puede desembolsar sin disposición")
        void disbursingRequiresADisposition() {
            assertThatThrownBy(() -> at(PlacementStatus.APPROVED).markDisbursing(null))
                    .isInstanceOf(PlacementValidationException.class);
        }

        @Test
        @DisplayName("la disposición rechazada por línea insuficiente cae en FAILED con motivo")
        void insufficientLineEndsInFailed() {
            Placement p = at(PlacementStatus.DISBURSING);

            p.fail("Línea insuficiente: disposición rechazada por credit-portfolio");

            assertThat(p.getStatus()).isEqualTo(PlacementStatus.FAILED);
            assertThat(p.getStatusReason()).contains("Línea insuficiente");
        }
    }

    @Nested
    @DisplayName("el alta previa")
    class Draft {

        @Test
        @DisplayName("nace INVITED, sin expediente y sin tocar la línea")
        void startsInvitedWithoutFileOrLine() {
            Placement p = draft();

            assertThat(p.getStatus()).isEqualTo(PlacementStatus.INVITED);
            assertThat(p.hasFile()).isFalse();
            assertThat(p.getBeneficiaryProspectId()).as("el prospecto no se crea al invitar").isNull();
            assertThat(p.getStatus().consumesLine()).isFalse();
            assertThat(p.getPlacementId()).isNotNull();
        }

        @Test
        @DisplayName("el celular tiene que ser de 10 dígitos")
        void phoneMustBeTenDigits() {
            assertThatThrownBy(() -> Placement.draft(DISTRIBUTOR, LINE, "María Luisa", "554182903",
                    null, new BigDecimal("18000"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("10 dígitos");

            assertThatThrownBy(() -> Placement.draft(DISTRIBUTOR, LINE, "María Luisa", "55 4182 9037",
                    null, new BigDecimal("18000"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null))
                    .isInstanceOf(PlacementValidationException.class);
        }

        @Test
        @DisplayName("el monto mínimo del producto se respeta desde el alta")
        void amountFloorIsEnforcedAtDraft() {
            assertThatThrownBy(() -> Placement.draft(DISTRIBUTOR, LINE, "María Luisa", "5541829037",
                    null, new BigDecimal("4999"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("El monto mínimo de una colocación es 5000");

            assertThat(Placement.draft(DISTRIBUTOR, LINE, "María Luisa", "5541829037",
                    null, new BigDecimal("5000"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null).getAmount())
                    .isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("nombre en blanco y plazo no positivo se rechazan")
        void nameAndTermAreValidated() {
            assertThatThrownBy(() -> Placement.draft(DISTRIBUTOR, LINE, "   ", "5541829037",
                    null, new BigDecimal("18000"), 12, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null))
                    .isInstanceOf(PlacementValidationException.class);

            assertThatThrownBy(() -> Placement.draft(DISTRIBUTOR, LINE, "María Luisa", "5541829037",
                    null, new BigDecimal("18000"), 0, PAYMENT, VerificationMode.SELF_SERVICE_LINK, EXPIRY, LIMITS, null))
                    .isInstanceOf(PlacementValidationException.class);
        }
    }

    @Nested
    @DisplayName("el veredicto de identidad — el juicio de Kredius, aparte del de la distribuidora")
    class IdentityReview {

        @Test
        @DisplayName("sin identidad comprobada no se aprueba, aunque el riesgo esté firmado")
        void approvalNeedsVerifiedIdentity() {
            Placement p = at(PlacementStatus.BUREAU_READY);
            // El helper la deja verificada; se revierte a pendiente para probar la regla.
            Placement sinRevisar = draft();
            sinRevisar.startKyc();
            sinRevisar.completeKyc(PROSPECT, PARTY, CLABE);
            sinRevisar.bureauReady();

            assertThat(sinRevisar.getIdentityDecision()).isEqualTo(IdentityDecision.PENDING);
            assertThatThrownBy(() -> sinRevisar.approve(true))
                    .isInstanceOf(IdentityNotVerifiedException.class);
            assertThat(sinRevisar.getStatus()).isEqualTo(PlacementStatus.BUREAU_READY);

            // Y con identidad rechazada tampoco: no es «falta revisar», es «no es ella».
            sinRevisar.reviewIdentity(IdentityDecision.REJECTED, VerificationSource.MANUAL,
                    "analista-1", "La selfie no coincide con la INE");
            assertThatThrownBy(() -> sinRevisar.approve(true))
                    .isInstanceOf(IdentityNotVerifiedException.class);

            // Con identidad verificada sí aprueba.
            p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);
            p.approve(true);
            assertThat(p.getStatus()).isEqualTo(PlacementStatus.APPROVED);
        }

        @Test
        @DisplayName("las dos condiciones son de responsables distintos y ninguna sustituye a la otra")
        void riskAndIdentityAreIndependent() {
            Placement p = at(PlacementStatus.BUREAU_READY);
            p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "analista-1", null);

            // Identidad sí, riesgo no → no aprueba.
            assertThatThrownBy(() -> p.approve(false))
                    .isInstanceOf(PlacementValidationException.class)
                    .hasMessageContaining("aceptación expresa del riesgo");
        }

        @Test
        @DisplayName("todo dictamen lleva autor, origen y —si rechaza— motivo")
        void everyVerdictIsAccountable() {
            Placement p = at(PlacementStatus.KYC_COMPLETED);

            assertThatThrownBy(() -> p.reviewIdentity(IdentityDecision.VERIFIED,
                    VerificationSource.MANUAL, "  ", null))
                    .isInstanceOf(IdentityReviewException.class).hasMessageContaining("autor");

            assertThatThrownBy(() -> p.reviewIdentity(IdentityDecision.VERIFIED, null, "analista", null))
                    .isInstanceOf(IdentityReviewException.class).hasMessageContaining("origen");

            assertThatThrownBy(() -> p.reviewIdentity(IdentityDecision.REJECTED,
                    VerificationSource.MANUAL, "analista", null))
                    .isInstanceOf(IdentityReviewException.class).hasMessageContaining("motivo");

            assertThatThrownBy(() -> p.reviewIdentity(IdentityDecision.PENDING,
                    VerificationSource.MANUAL, "analista", null))
                    .isInstanceOf(IdentityReviewException.class);
        }

        @Test
        @DisplayName("no se dictamina antes de que exista expediente")
        void noVerdictWithoutAFile() {
            Placement reciénInvitada = at(PlacementStatus.INVITED);

            assertThatThrownBy(() -> reciénInvitada.reviewIdentity(IdentityDecision.VERIFIED,
                    VerificationSource.MANUAL, "analista", null))
                    .isInstanceOf(IdentityReviewException.class)
                    .hasMessageContaining("expediente");
        }

        @Test
        @DisplayName("el veredicto guarda quién y cuándo, y el origen que declaró quien firmó")
        void theVerdictIsRecordedWithItsAuthor() {
            Placement p = at(PlacementStatus.KYC_COMPLETED);

            p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.PROVIDER_ESCALATED,
                    "ana.lopez", null);

            assertThat(p.getIdentityDecision()).isEqualTo(IdentityDecision.VERIFIED);
            assertThat(p.getIdentityDecidedBy()).isEqualTo("ana.lopez");
            assertThat(p.getIdentityDecidedAt()).isNotNull();
            // El origen NO se deduce de la bandera vigente: lo declara quien firma, para que un
            // expediente viejo pueda decir quién lo revisó entonces.
            assertThat(p.getIdentityVerificationSource()).isEqualTo(VerificationSource.PROVIDER_ESCALATED);
            assertThat(p.getIdentityRejectionReason()).isNull();
        }

        @Test
        @DisplayName("un rechazo conserva su motivo; una verificación no arrastra el de antes")
        void rejectionKeepsItsReason() {
            Placement p = at(PlacementStatus.KYC_COMPLETED);

            p.reviewIdentity(IdentityDecision.REJECTED, VerificationSource.MANUAL,
                    "ana.lopez", "La INE está vencida");
            assertThat(p.getIdentityRejectionReason()).isEqualTo("La INE está vencida");

            // Vuelve a subir su INE y se re-dictamina: el motivo viejo no puede quedarse pegado.
            p.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "ana.lopez", null);
            assertThat(p.getIdentityRejectionReason()).isNull();
        }

        @Test
        @DisplayName("IdentityStatus honra el veredicto y deja de inventarse un VERIFIED")
        void identityStatusHonoursTheVerdict() {
            // Antes, completar el KYC bastaba para figurar como verificada. Ya no: entregar
            // documentos no es que alguien los haya mirado.
            Placement sinRevisar = draft();
            sinRevisar.startKyc();
            sinRevisar.completeKyc(PROSPECT, PARTY, CLABE);
            assertThat(IdentityStatus.of(sinRevisar)).isEqualTo(IdentityStatus.IN_PROGRESS);

            sinRevisar.reviewIdentity(IdentityDecision.VERIFIED, VerificationSource.MANUAL, "a", null);
            assertThat(IdentityStatus.of(sinRevisar)).isEqualTo(IdentityStatus.VERIFIED);

            sinRevisar.reviewIdentity(IdentityDecision.REJECTED, VerificationSource.MANUAL, "a", "no coincide");
            assertThat(IdentityStatus.of(sinRevisar)).isEqualTo(IdentityStatus.NOT_VERIFIED);
        }
    }
}
