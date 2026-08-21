package com.fintech.beneficiary.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Una colocación: un distribuidor le presta a una persona con cargo a su línea revolvente.
 *
 * <p>El agregado es la colocación y no el beneficiario, porque el diseño permite explícitamente
 * «enviarle otro préstamo» a alguien que ya es beneficiario: una persona tiene <i>n</i>
 * colocaciones a lo largo del tiempo, cada una con su propio expediente de decisión.
 *
 * <p><b>Al invitar no se crea prospecto.</b> El alta previa vive entera aquí, con los tres campos
 * que el distribuidor teclea en la pantalla 18 (nombre como en su INE, celular, cómo la conoce) y
 * nada más. Crear un prospecto con eso llenaría origination de fantasmas y —lo grave— dispararía el
 * prefetch de buró de scoring, que se engancha a {@code origination.prospect-created}, antes de que
 * ella haya autorizado nada. El prospecto {@code INDIVIDUAL} y su Party nacen en
 * {@link PlacementStatus#KYC_COMPLETED}, con el expediente completo y el consentimiento firmado.
 *
 * <p>La deudora frente a Kredius es la distribuidora: hay una sola cuenta de crédito, la suya, y
 * cada colocación es una {@code Disposition THIRD_PARTY_CREDIT} de esa línea. Lo que queda a nombre
 * de la beneficiaria es el contrato de colocación, que es un documento del expediente y no una
 * exposición en cartera.
 */
@Entity
@Table(schema = "beneficiary", name = "placements")
public class Placement {

    /** El diseño pide «su celular a 10 dígitos» y advierte que tiene que ser suyo, no del distribuidor. */
    private static final Pattern PHONE = Pattern.compile("^\\d{10}$");

    @Id
    @Column(name = "placement_id", nullable = false, updatable = false)
    private UUID placementId;

    @Column(name = "distributor_party_id", nullable = false, updatable = false)
    private UUID distributorPartyId;

    /** La línea revolvente contra la que se coloca. Se resuelve al invitar y ya no cambia. */
    @Column(name = "distributor_credit_account_id", nullable = false, updatable = false)
    private UUID distributorCreditAccountId;

    // ── Alta previa — los tres campos de la pantalla 18 ───────────────────────────────────────

    @Column(name = "beneficiary_full_name", nullable = false)
    private String beneficiaryFullName;

    @Column(name = "beneficiary_phone", nullable = false)
    private String beneficiaryPhone;

    @Column(name = "beneficiary_relationship")
    private String beneficiaryRelationship;

    // ── Expediente real — nulos hasta KYC_COMPLETED ───────────────────────────────────────────

    @Column(name = "beneficiary_prospect_id")
    private UUID beneficiaryProspectId;

    @Column(name = "beneficiary_party_id")
    private UUID beneficiaryPartyId;

    /** Su CLABE. Sin ella no hay a dónde depositar, por eso el KYC no cierra sin capturarla. */
    @Column(name = "beneficiary_clabe")
    private String beneficiaryClabe;

    // ── Términos ─────────────────────────────────────────────────────────────────────────────

    @Column(name = "amount", nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "term_fortnights", nullable = false, updatable = false)
    private int termFortnights;

    /**
     * Lo que ella paga cada quincena. Lo calcula el backend contra la configuración del producto
     * —tasa, cadencia y método de amortización— y se congela aquí al crear la colocación.
     *
     * <p>La app lo estima mientras el usuario arrastra el slider, pero la cifra que va al contrato
     * es ésta: si el teléfono y el servidor redondearan distinto, el distribuidor le prometería a
     * su clienta un número y ella firmaría otro.
     */
    @Column(name = "fortnightly_payment", nullable = false, updatable = false)
    private BigDecimal fortnightlyPayment;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_mode", nullable = false, updatable = false)
    private VerificationMode verificationMode;

    /**
     * Cuándo vence la liga. Vive en la colocación y no en el token porque es su ventana, no su
     * secreto: la colocación sabe hasta cuándo puede ser aceptada, y {@code PlacementInvite}
     * guarda con qué. Reenviar la reinicia.
     */
    @Column(name = "invite_expires_at", nullable = false)
    private Instant inviteExpiresAt;

    // ── Estado ───────────────────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PlacementStatus status;

    @Column(name = "status_reason")
    private String statusReason;

    /** La disposición que descontó la línea. Nulo hasta DISBURSING. */
    @Column(name = "disposition_id")
    private UUID dispositionId;

    // ── Veredicto de identidad ───────────────────────────────────────────────────────────────
    //
    // Es un juicio propio y NO se deriva del avance de la colocación. Antes se derivaba, y por eso
    // no había dónde poner la decisión de un analista: «terminó su KYC» y «comprobamos que es
    // ella» eran el mismo dato. Son dos preguntas de dos responsables distintos.

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_decision", nullable = false, length = 20)
    private IdentityDecision identityDecision = IdentityDecision.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_verification_source", length = 25)
    private VerificationSource identityVerificationSource;

    @Column(name = "identity_decided_by", length = 120)
    private String identityDecidedBy;

    @Column(name = "identity_decided_at")
    private Instant identityDecidedAt;

    @Column(name = "identity_rejection_reason", length = 500)
    private String identityRejectionReason;

    /**
     * Por qué esta identidad está donde está.
     *
     * <p>En manual dice que así está configurado; en automático dice qué falló —«PROVEEDOR_NO_
     * DISPONIBLE», «UMBRAL_NO_ALCANZADO: facial 0.82 &lt; 0.90», «DOCUMENTO_NO_VALIDADO: INE»—.
     * Es lo que el analista lee <b>antes</b> de abrir el expediente: sin esto su cola sería una
     * lista de casos sin pista de qué mirar, que es lo mismo que revisarlos todos de cero.
     */
    @Column(name = "identity_review_notes", length = 1000)
    private String identityReviewNotes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Cuándo el distribuidor decidió — aprobar o rechazar. */
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Placement() {}

    /**
     * El alta previa. Nace {@code INVITED}: la liga todavía no existe (la acuña
     * {@code PlacementInvite}) y la línea no se toca.
     */
    public static Placement draft(UUID distributorPartyId, UUID distributorCreditAccountId,
                                  String beneficiaryFullName, String beneficiaryPhone,
                                  String beneficiaryRelationship, BigDecimal amount,
                                  int termFortnights, BigDecimal fortnightlyPayment,
                                  VerificationMode verificationMode, Instant inviteExpiresAt,
                                  PlacementLimits limits, BigDecimal availableLine) {

        requireNotNull(distributorPartyId, "distributorPartyId");
        requireNotNull(distributorCreditAccountId, "distributorCreditAccountId");
        requireNotNull(verificationMode, "verificationMode");
        requireNotNull(inviteExpiresAt, "inviteExpiresAt");
        requireNotNull(limits, "limits");

        if (beneficiaryFullName == null || beneficiaryFullName.isBlank()) {
            throw new PlacementValidationException("El nombre de la beneficiaria es obligatorio");
        }
        if (beneficiaryPhone == null || !PHONE.matcher(beneficiaryPhone).matches()) {
            throw new PlacementValidationException(
                    "El celular de la beneficiaria debe ser de 10 dígitos, fue: " + beneficiaryPhone);
        }
        // Monto y plazo se validan contra la configuración del producto, no contra constantes:
        // el tope por beneficiario, el mínimo y los escalones se cambian sin redeploy.
        limits.validateAmount(amount, availableLine);
        limits.validateTerm(termFortnights);

        if (fortnightlyPayment == null || fortnightlyPayment.signum() <= 0) {
            throw new PlacementValidationException(
                    "El pago quincenal debe venir calculado y ser positivo, fue: " + fortnightlyPayment);
        }

        Placement p = new Placement();
        p.placementId                 = UUID.randomUUID();
        p.distributorPartyId          = distributorPartyId;
        p.distributorCreditAccountId  = distributorCreditAccountId;
        p.beneficiaryFullName         = beneficiaryFullName.trim();
        p.beneficiaryPhone            = beneficiaryPhone;
        p.beneficiaryRelationship     = beneficiaryRelationship == null ? null : beneficiaryRelationship.trim();
        p.amount                      = amount;
        p.termFortnights              = termFortnights;
        p.fortnightlyPayment          = fortnightlyPayment;
        p.verificationMode            = verificationMode;
        p.inviteExpiresAt             = inviteExpiresAt;
        p.status                      = PlacementStatus.INVITED;
        p.createdAt                   = Instant.now();
        p.updatedAt                   = p.createdAt;
        return p;
    }

    // ── Transiciones ─────────────────────────────────────────────────────────────────────────

    /** La beneficiaria abrió la liga y pasó su OTP. */
    public void startKyc() {
        transitionTo(PlacementStatus.KYC_IN_PROGRESS, null);
    }

    /**
     * Los 7 pasos quedaron completos y el expediente ya existe en origination y party.
     *
     * <p>Es todo o nada: si esto se invoca, es porque el prospecto, el Party, la relación con el
     * distribuidor y los consentimientos quedaron grabados. La CLABE es obligatoria aquí y no antes
     * porque es el último dato que ella captura, pero sin ella la colocación no podría desembolsar
     * y no tiene caso dejarla avanzar.
     */
    public void completeKyc(UUID prospectId, UUID partyId, String clabe) {
        requireNotNull(prospectId, "prospectId");
        requireNotNull(partyId, "partyId");
        if (clabe == null || !clabe.matches("\\d{18}")) {
            throw new PlacementValidationException("La CLABE debe ser de 18 dígitos, fue: " + clabe);
        }
        this.beneficiaryProspectId = prospectId;
        this.beneficiaryPartyId    = partyId;
        this.beneficiaryClabe      = clabe;
        transitionTo(PlacementStatus.KYC_COMPLETED, null);
    }

    /** Scoring respondió: el reporte está disponible para el distribuidor. */
    public void bureauReady() {
        transitionTo(PlacementStatus.BUREAU_READY, null);
    }

    /**
     * El distribuidor decidió colocar.
     *
     * <p>La firma exige {@code riskAcknowledged} explícito porque Kredius no aprueba ni rechaza: la
     * decisión y el riesgo son suyos, y la casilla de la pantalla 25 es la evidencia de que lo
     * aceptó. Una aprobación sin esa casilla no es una aprobación incompleta, es una que no existe.
     */
    public void approve(boolean riskAcknowledged) {
        // La arista se valida ANTES que las precondiciones de negocio. Aprobar una colocación
        // vencida es un error de flujo, y decirle a quien lo intenta «falta verificar la identidad»
        // lo mandaría a resolver algo que no arregla nada.
        if (!status.canTransitionTo(PlacementStatus.APPROVED)) {
            throw new InvalidPlacementTransitionException(placementId, status, PlacementStatus.APPROVED);
        }
        if (!riskAcknowledged) {
            throw new PlacementValidationException(
                    "No se puede aprobar sin la aceptación expresa del riesgo por parte del distribuidor");
        }
        // Las dos condiciones son de responsables distintos y ninguna sustituye a la otra: la
        // distribuidora asume el riesgo, Kredius comprueba la identidad. Aprobar dispara el
        // depósito, y **lo único que detiene un depósito es una identidad no comprobada** — así que
        // el corte va aquí y no más adelante: rebotar en la disposición le daría a la distribuidora
        // un fallo mudo en vez de un motivo.
        if (identityDecision != IdentityDecision.VERIFIED) {
            throw new IdentityNotVerifiedException(placementId, identityDecision);
        }
        this.decidedAt = Instant.now();
        transitionTo(PlacementStatus.APPROVED, null);
    }

    /**
     * Registra el veredicto de identidad.
     *
     * <p>Se puede dictaminar en cuanto hay expediente y hasta que la colocación muere. No se exige
     * un estado concreto a propósito: la mesa de KYC trabaja a su ritmo y la colocación al suyo, y
     * atarlos obligaría al analista a esperar a que la distribuidora mire su pantalla.
     */
    /**
     * Registra por qué esta identidad necesita —o no— revisión humana.
     *
     * <p>Lo escribe la evaluación automática, no una persona. Se guarda aunque el veredicto quede
     * pendiente: <b>ese es justo el caso en que sirve</b>.
     */
    public void recordVerificationNotes(String notes) {
        this.identityReviewNotes = notes;
        this.updatedAt = Instant.now();
    }

    public void reviewIdentity(IdentityDecision decision, VerificationSource source,
                               String decidedBy, String rejectionReason) {
        if (decision == null || decision == IdentityDecision.PENDING) {
            throw new IdentityReviewException("El dictamen debe ser VERIFIED o REJECTED");
        }
        if (source == null) {
            throw new IdentityReviewException("Todo dictamen declara su origen");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IdentityReviewException("Todo dictamen lleva autor");
        }
        if (decision == IdentityDecision.REJECTED
                && (rejectionReason == null || rejectionReason.isBlank())) {
            throw new IdentityReviewException("Un rechazo de identidad siempre lleva motivo");
        }
        if (!hasFile()) {
            // Sin expediente no hay nada que revisar: la beneficiaria ni siquiera ha entregado sus
            // datos. Dictaminar aquí sería firmar sobre una carpeta vacía.
            throw new IdentityReviewException(
                    "No se puede dictaminar la identidad antes de que exista expediente");
        }

        this.identityDecision           = decision;
        this.identityVerificationSource = source;
        this.identityDecidedBy          = decidedBy;
        this.identityDecidedAt          = Instant.now();
        this.identityRejectionReason    =
                decision == IdentityDecision.REJECTED ? rejectionReason : null;
        this.updatedAt = Instant.now();
    }

    /** El distribuidor vio el historial y decidió no colocar. */
    public void reject(String reason) {
        this.decidedAt = Instant.now();
        transitionTo(PlacementStatus.REJECTED, reason);
    }

    /** Disposición pedida a wallet. La última palabra sobre el cupo la tiene credit-portfolio. */
    public void markDisbursing(UUID dispositionId) {
        requireNotNull(dispositionId, "dispositionId");
        this.dispositionId = dispositionId;
        transitionTo(PlacementStatus.DISBURSING, null);
    }

    /**
     * Sustituye el marcador por el id real de la disposición.
     *
     * <p>Al pedir la disposición, wallet responde 202 y todavía no existe el id. Para no dejar el
     * campo nulo —el CHECK de la base lo prohíbe en {@code DISBURSING}— se guarda como marcador el
     * del propio placement. El id real llega después, en {@code disposition-completed}, y es la
     * única llave que ata esta colocación con su calendario en cartera.
     *
     * <p>Sin esta reconciliación el marcador se queda para siempre: la colocación pide su
     * calendario con un id que no existe, recibe una lista vacía y se pinta <b>AL CORRIENTE</b>
     * aunque esté vencida. No transiciona de estado, porque no es un cambio de ciclo de vida sino
     * la corrección de una llave que se escribió provisional.
     */
    public void reconcileDispositionId(UUID real) {
        requireNotNull(real, "dispositionId");
        this.dispositionId = real;
    }

    /** El SPEI llegó a su CLABE. */
    public void markDisbursed() {
        this.disbursedAt = Instant.now();
        transitionTo(PlacementStatus.DISBURSED, null);
    }

    /**
     * Ella terminó de pagar: el calendario de la disposición quedó saldado.
     *
     * <p>Lo dispara credit-portfolio, que es quien lleva las cuotas. La colocación no se entera
     * sola, y por eso {@code DISBURSED} no puede ser terminal: entre el depósito y el último pago
     * hay meses de vida que el agregado tiene que poder representar.
     */
    public void markPaidOff() {
        transitionTo(PlacementStatus.PAID_OFF, null);
    }

    /**
     * Reenviar la liga reinicia sus 7 días.
     *
     * <p>Es lo que promete el contrato de la app. El riesgo de vigencia perpetua a punta de
     * reenvíos no se contiene aquí sino con el tope de reenvíos por colocación, que es donde
     * pertenece: esta operación no sabe cuántas veces se ha invocado, y fingir que sí la volvería
     * una mala guardiana de una regla que no puede ver.
     */
    public void restartInviteWindow(Instant newExpiry) {
        requireNotNull(newExpiry, "newExpiry");
        if (!status.inviteIsLive()) {
            throw new InvalidPlacementTransitionException(placementId, status, status);
        }
        this.inviteExpiresAt = newExpiry;
        this.updatedAt       = Instant.now();
    }

    /** Si la liga ya venció a la fecha dada. Lo consulta el barrido de vencimiento. */
    public boolean inviteHasExpired(Instant at) {
        return status.inviteIsLive() && !inviteExpiresAt.isAfter(at);
    }

    /** Pasaron los 7 días de la liga sin que ella completara su KYC. */
    public void expire() {
        transitionTo(PlacementStatus.EXPIRED, "La liga venció sin completar la verificación");
    }

    /** El distribuidor revocó la liga. Sólo cabe antes de que haya expediente. */
    public void cancel(String reason) {
        transitionTo(PlacementStatus.CANCELLED, reason);
    }

    /** Algo se rompió: KYC fallido, buró indisponible, disposición rechazada. Siempre con motivo. */
    public void fail(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new PlacementValidationException("Una colocación fallida siempre lleva motivo");
        }
        transitionTo(PlacementStatus.FAILED, reason);
    }

    private void transitionTo(PlacementStatus target, String reason) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidPlacementTransitionException(placementId, status, target);
        }
        this.status       = target;
        this.statusReason = reason;
        this.updatedAt    = Instant.now();
    }

    private static void requireNotNull(Object value, String field) {
        if (value == null) throw new PlacementValidationException(field + " es obligatorio");
    }

    // ── Consultas de dominio ─────────────────────────────────────────────────────────────────

    /** Si el token de esta colocación todavía puede acuñar sesión de invitado. */
    public boolean inviteIsLive() {
        return status.inviteIsLive();
    }

    public boolean hasFile() {
        return beneficiaryPartyId != null;
    }

    // ── Getters ──────────────────────────────────────────────────────────────────────────────

    public UUID getPlacementId()                { return placementId; }
    public UUID getDistributorPartyId()         { return distributorPartyId; }
    public UUID getDistributorCreditAccountId() { return distributorCreditAccountId; }
    public String getBeneficiaryFullName()      { return beneficiaryFullName; }
    public String getBeneficiaryPhone()         { return beneficiaryPhone; }
    public String getBeneficiaryRelationship()  { return beneficiaryRelationship; }
    public UUID getBeneficiaryProspectId()      { return beneficiaryProspectId; }
    public UUID getBeneficiaryPartyId()         { return beneficiaryPartyId; }
    public String getBeneficiaryClabe()         { return beneficiaryClabe; }
    public BigDecimal getAmount()               { return amount; }
    public int getTermFortnights()              { return termFortnights; }
    public BigDecimal getFortnightlyPayment()   { return fortnightlyPayment; }
    public Instant getInviteExpiresAt()         { return inviteExpiresAt; }
    public VerificationMode getVerificationMode() { return verificationMode; }
    public PlacementStatus getStatus()          { return status; }
    public String getStatusReason()             { return statusReason; }
    public UUID getDispositionId()              { return dispositionId; }
    public Instant getCreatedAt()               { return createdAt; }
    public Instant getUpdatedAt()               { return updatedAt; }
    public Instant getDecidedAt()               { return decidedAt; }
    public Instant getDisbursedAt()             { return disbursedAt; }
    public IdentityDecision getIdentityDecision()          { return identityDecision; }
    public VerificationSource getIdentityVerificationSource() { return identityVerificationSource; }
    public String getIdentityDecidedBy()                   { return identityDecidedBy; }
    public Instant getIdentityDecidedAt()                  { return identityDecidedAt; }
    public String getIdentityRejectionReason()             { return identityRejectionReason; }
    public String getIdentityReviewNotes()                 { return identityReviewNotes; }
    public long getVersion()                    { return version; }
}
