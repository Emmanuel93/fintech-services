package com.fintech.creditportfolio.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * El veredicto de KYC y listas de una persona, proyectado desde compliance (D16).
 *
 * <p>Existe aquí para poder <b>frenar una disposición</b> sin salir a preguntar. Originar ocurre una
 * vez; disponer ocurre muchas veces durante años, y las listas cambian entre una y otra — así que
 * la disposición es el control recurrente y está en el camino del dinero, donde una llamada
 * síncrona que puede no contestar es lo último que conviene.
 */
@Entity
@Table(name = "party_verifications", schema = "credit_portfolio")
public class PartyVerification {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false)
    private UUID partyId;

    @Column(name = "identity_status", length = 20, nullable = false)
    private String identityStatus;

    @Column(name = "screening_status", length = 20, nullable = false)
    private String screeningStatus;

    @Column(name = "allows_to_proceed", nullable = false)
    private boolean allowsToProceed;

    @Column(name = "block_reason", length = 200)
    private String blockReason;

    /** Contra qué versiones de lista se calculó. Es lo que se copia en cada disposición. */
    @Column(name = "screened_list_versions", length = 1000)
    private String screenedListVersions;

    /** Cuándo se calculó. Su distancia con el momento de la disposición es la antigüedad del dato. */
    @Column(name = "verdict_at")
    private Instant verdictAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PartyVerification() { }

    public static PartyVerification of(UUID partyId, String identityStatus, String screeningStatus,
                                       boolean allowsToProceed, String blockReason) {
        return of(partyId, identityStatus, screeningStatus, allowsToProceed, blockReason, null, null);
    }

    public static PartyVerification of(UUID partyId, String identityStatus, String screeningStatus,
                                       boolean allowsToProceed, String blockReason,
                                       String screenedListVersions, Instant verdictAt) {
        PartyVerification v = new PartyVerification();
        v.partyId = partyId;
        v.apply(identityStatus, screeningStatus, allowsToProceed, blockReason,
                screenedListVersions, verdictAt);
        return v;
    }

    public void apply(String identityStatus, String screeningStatus,
                      boolean allowsToProceed, String blockReason) {
        apply(identityStatus, screeningStatus, allowsToProceed, blockReason,
              this.screenedListVersions, this.verdictAt);
    }

    public void apply(String identityStatus, String screeningStatus, boolean allowsToProceed,
                      String blockReason, String screenedListVersions, Instant verdictAt) {
        this.screenedListVersions = screenedListVersions;
        this.verdictAt            = verdictAt;
        this.identityStatus  = identityStatus;
        this.screeningStatus = screeningStatus;
        this.allowsToProceed = allowsToProceed;
        this.blockReason     = blockReason == null ? null
                : blockReason.substring(0, Math.min(200, blockReason.length()));
        this.updatedAt       = Instant.now();
    }

    /**
     * Si esta persona puede <b>disponer</b>. No es lo mismo que poder originar.
     *
     * <h2>Por qué la identidad no se vuelve a exigir aquí</h2>
     *
     * El KYC estableció quién es, <b>una vez</b>, al originar. Repetirlo en cada disposición sería
     * pedir una selfie para sacar dinero de la propia línea todos los meses: no añade seguridad y
     * garantiza que la gente deje de usar el producto. Lo que hace falta en cada disposición es
     * saber que quien la pide es esa misma persona, y eso lo resuelve la <b>autenticación</b>
     * —NIP y OTP al teléfono registrado—, no la biometría.
     *
     * <p>El KYC es el enrolamiento; la autenticación es el uso. Pedir enrolamiento en cada uso es la
     * forma más común de que un control se apague «por fricción».
     *
     * <h2>Lo que sí bloquea</h2>
     *
     * <ul>
     *   <li><b>Las listas, siempre.</b> El AML no depende de la persona sino de la lista, y la lista
     *       cambia: alguien limpio al abrir su línea puede estar publicado seis meses después. Como
     *       originar ocurre una vez y disponer ocurre decenas de veces durante años, <b>ésta es la
     *       comprobación recurrente</b> — y la última antes de que el dinero salga.</li>
     *   <li><b>Una identidad revocada.</b> Distinto de «no verificada»: es que se comprobó y después
     *       se cayó —fraude, suplantación—. Eso sí detiene.</li>
     * </ul>
     */
    public boolean allowsDisposition() {
        return "CLEARED".equals(screeningStatus) && !"REJECTED".equals(identityStatus);
    }

    /** Por qué no puede disponer, para que el rechazo lleve motivo y no sólo un no. */
    public String dispositionBlockReason() {
        if (allowsDisposition()) return null;
        if ("REJECTED".equals(identityStatus)) return "identidad revocada";
        return "listas " + screeningStatus;
    }

    public String getScreenedListVersions() { return screenedListVersions; }
    public Instant getVerdictAt()           { return verdictAt; }

    public UUID getPartyId()           { return partyId; }
    public String getIdentityStatus()  { return identityStatus; }
    public String getScreeningStatus() { return screeningStatus; }
    public boolean allowsToProceed()   { return allowsToProceed; }
    public String getBlockReason()     { return blockReason; }
    public Instant getUpdatedAt()      { return updatedAt; }
}
