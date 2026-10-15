package com.fintech.compliance.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una carga de una lista. <b>Es una versión, no una mutación.</b>
 *
 * <h2>Por qué versionar en vez de actualizar en sitio</h2>
 *
 * Una lista a medio cargar es peor que la de ayer completa: quien todavía no se ha insertado sale
 * «limpio», y salir limpio es exactamente lo que el control existe para impedir. Cargar contra la
 * tabla viva convierte cualquier fallo a mitad de archivo —red, formato, disco— en una ventana en la
 * que se aprueba a gente que está en la lista, y esa ventana no deja rastro.
 *
 * <p>Así que se carga en una versión nueva, que nadie consulta, y sólo cuando termina completa se
 * activa. La anterior queda `SUPERSEDED` y no se borra: un veredicto emitido ayer tiene que poder
 * explicarse contra la lista que se usó ayer, no contra la de hoy.
 *
 * <h2>El hash es la llave de la idempotencia</h2>
 *
 * Las sincronizaciones se reintentan, se solapan y se disparan a mano cuando alguien duda. Cargar
 * dos veces el mismo archivo tiene que no hacer nada, y la única forma de saber que es el mismo
 * archivo es su contenido — no su nombre ni su fecha, que los publicadores cambian sin que cambie
 * el contenido.
 */
@Entity
@Table(name = "watchlist_versions", schema = "compliance")
public class WatchlistVersion {

    @Id
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    /** Qué lista, en el vocabulario del publicador: `OFAC-SDN`, `UIF-PLD`, `SAT-69B`, `PEP-MX`. */
    @Column(name = "source_code", length = 40, nullable = false, updatable = false)
    private String sourceCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false, updatable = false)
    private WatchlistKind kind;

    /** SHA-256 del archivo. Dos cargas del mismo contenido son la misma carga. */
    @Column(name = "file_hash", length = 64, nullable = false, updatable = false)
    private String fileHash;

    @Column(name = "file_name", length = 255, updatable = false)
    private String fileName;

    /**
     * La fecha con la que el publicador emitió la lista, no la fecha en que la cargamos.
     *
     * <p>Son distintas y la que importa para explicar un veredicto es la primera: «se consultó la
     * lista publicada el 3 de septiembre» dice algo; «se consultó el 14 de septiembre» no dice
     * contra qué.
     */
    @Column(name = "published_on")
    private LocalDate publishedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private WatchlistVersionStatus status;

    @Column(name = "total_rows",    nullable = false) private int totalRows;
    @Column(name = "accepted_rows", nullable = false) private int acceptedRows;
    @Column(name = "rejected_rows", nullable = false) private int rejectedRows;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    // ── Cobertura de identificadores ─────────────────────────────────────────────────────────
    //
    // Cruzar por CURP es una identificación y no produce homónimos — pero sólo sobre las entradas
    // que lo traen, y eso lo decide el publicador. Sin este número, «siempre cruzamos por CURP»
    // puede ser un control que nunca dispara.

    @Column(name = "entries_with_curp") private Integer entriesWithCurp;
    @Column(name = "entries_with_rfc")  private Integer entriesWithRfc;
    @Column(name = "entries_with_dob")  private Integer entriesWithDob;

    /** Cuántas entradas SIN CURP ganaron una raíz derivada. Es lo que el cruce por raíz recupera. */
    @Column(name = "entries_with_curp_root") private Integer entriesWithCurpRoot;

    @Column(name = "started_at",   nullable = false) private Instant startedAt;
    @Column(name = "finished_at")  private Instant finishedAt;
    @Column(name = "activated_at") private Instant activatedAt;

    protected WatchlistVersion() { }

    public static WatchlistVersion start(String sourceCode, WatchlistKind kind,
                                         String fileHash, String fileName, LocalDate publishedOn) {
        WatchlistVersion v = new WatchlistVersion();
        v.versionId   = UUID.randomUUID();
        v.sourceCode  = sourceCode;
        v.kind        = kind;
        v.fileHash    = fileHash;
        v.fileName    = fileName;
        v.publishedOn = publishedOn;
        v.status      = WatchlistVersionStatus.LOADING;
        v.startedAt   = Instant.now();
        return v;
    }

    public void countRow(boolean accepted) {
        totalRows++;
        if (accepted) acceptedRows++; else rejectedRows++;
    }

    /**
     * Termina la carga con éxito.
     *
     * <p><b>Cero filas buenas no es una lista vacía, es una carga fallida.</b> Un archivo del que no
     * se pudo leer nada —layout cambiado, encoding roto, descarga truncada a cero— se parece mucho a
     * una lista legítimamente vacía, y confundirlos activaría una versión sin nadie: todo el mundo
     * saldría limpio. Una lista de sancionados nunca está vacía; si lo parece, es que falló.
     */
    public void finishLoaded() {
        requireLoading();
        if (acceptedRows == 0) {
            fail("La carga no produjo ninguna fila válida de " + totalRows + " leídas");
            throw new EmptyWatchlistLoadException(sourceCode, totalRows);
        }
        this.finishedAt = Instant.now();
        this.status     = WatchlistVersionStatus.LOADED;
    }

    public void fail(String reason) {
        this.status        = WatchlistVersionStatus.FAILED;
        this.failureReason = reason == null ? "sin motivo" : reason.substring(0, Math.min(500, reason.length()));
        this.finishedAt    = Instant.now();
    }

    /** Pasa a ser la vigente. Sólo desde `LOADED`: activar algo a medio cargar es el fallo que esto evita. */
    public void activate() {
        if (status != WatchlistVersionStatus.LOADED) {
            throw new IllegalStateException(
                    "Sólo se activa una versión LOADED; " + sourceCode + " está en " + status);
        }
        this.status      = WatchlistVersionStatus.ACTIVE;
        this.activatedAt = Instant.now();
    }

    public void supersede() {
        if (status == WatchlistVersionStatus.ACTIVE) {
            this.status = WatchlistVersionStatus.SUPERSEDED;
        }
    }

    public void recordIdentifierCoverage(int withCurp, int withRfc, int withDob, int withCurpRoot) {
        this.entriesWithCurp     = withCurp;
        this.entriesWithRfc      = withRfc;
        this.entriesWithDob      = withDob;
        this.entriesWithCurpRoot = withCurpRoot;
    }

    /**
     * Qué fracción de la lista se puede encontrar cruzando por CURP.
     *
     * <p>Es el número que convierte «siempre cruzamos por CURP» en una decisión informada: si sale
     * 0.03, se está comprobando contra el 3 % de la lista y el 97 % sale limpio sin que nada falle.
     */
    public BigDecimal curpCoverage() {
        if (acceptedRows == 0 || entriesWithCurp == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(entriesWithCurp)
                .divide(BigDecimal.valueOf(acceptedRows), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal rfcCoverage() {
        if (acceptedRows == 0 || entriesWithRfc == null) return BigDecimal.ZERO;
        return BigDecimal.valueOf(entriesWithRfc)
                .divide(BigDecimal.valueOf(acceptedRows), 4, RoundingMode.HALF_UP);
    }

    /**
     * Qué fracción de la lista se puede encontrar cruzando por CURP <b>o por su raíz derivada</b>.
     *
     * <p>Se publica junto a {@link #curpCoverage()} y no en su lugar, porque las dos dicen cosas
     * distintas: la primera es lo que el publicador entrega, la segunda es lo que se alcanza. La
     * diferencia entre ambas es exactamente lo que la derivación recupera — y si sale igual, es que
     * la lista tampoco trae fecha de nacimiento y la derivación no pudo hacer nada.
     */
    public BigDecimal curpReachCoverage() {
        if (acceptedRows == 0) return BigDecimal.ZERO;
        int alcanzadas = (entriesWithCurp == null ? 0 : entriesWithCurp)
                       + (entriesWithCurpRoot == null ? 0 : entriesWithCurpRoot);
        return BigDecimal.valueOf(alcanzadas)
                .divide(BigDecimal.valueOf(acceptedRows), 4, RoundingMode.HALF_UP);
    }

    public Integer getEntriesWithCurpRoot() { return entriesWithCurpRoot; }
    public Integer getEntriesWithCurp() { return entriesWithCurp; }
    public Integer getEntriesWithRfc()  { return entriesWithRfc; }
    public Integer getEntriesWithDob()  { return entriesWithDob; }

    /** Proporción de filas rechazadas, para comparar una carga contra la anterior. */
    public BigDecimal rejectionRate() {
        if (totalRows == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(rejectedRows)
                .divide(BigDecimal.valueOf(totalRows), 4, RoundingMode.HALF_UP);
    }

    private void requireLoading() {
        if (status != WatchlistVersionStatus.LOADING) {
            throw new IllegalStateException("La versión de " + sourceCode + " no está cargando: " + status);
        }
    }

    public UUID getVersionId()            { return versionId; }
    public String getSourceCode()         { return sourceCode; }
    public WatchlistKind getKind()        { return kind; }
    public String getFileHash()           { return fileHash; }
    public String getFileName()           { return fileName; }
    public LocalDate getPublishedOn()     { return publishedOn; }
    public WatchlistVersionStatus getStatus() { return status; }
    public int getTotalRows()             { return totalRows; }
    public int getAcceptedRows()          { return acceptedRows; }
    public int getRejectedRows()          { return rejectedRows; }
    public String getFailureReason()      { return failureReason; }
    public Instant getStartedAt()         { return startedAt; }
    public Instant getActivatedAt()       { return activatedAt; }
}
