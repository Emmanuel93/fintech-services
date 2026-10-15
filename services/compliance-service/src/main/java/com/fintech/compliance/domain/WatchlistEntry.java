package com.fintech.compliance.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Una persona o entidad en una lista, ya normalizada para poder cruzarla.
 *
 * <h2>Por qué casi todo es opcional menos el nombre</h2>
 *
 * Las listas de gobierno traen lo que traen. La de sancionados de un país puede no incluir fecha de
 * nacimiento; la del SAT trae RFC pero no CURP; una de PEP puede traer sólo nombre y cargo. Exigir
 * campos que el publicador no entrega convertiría la mitad del archivo en rechazos y dejaría fuera
 * justo a quien hay que encontrar.
 *
 * <p>El nombre es lo único obligatorio porque es lo único que todas traen — y es, a la vez, el peor
 * identificador que existe. De ahí que el cruce no pueda ser un booleano: ver
 * {@code docs/ANALISIS_KYC_AML_POR_PERSONA.md}.
 *
 * <h2>Los documentos se guardan cuando vienen, y valen mucho más que el nombre</h2>
 *
 * Un CURP o un RFC coincidente es una identificación; un nombre coincidente es una sospecha. Se
 * guardan aparte y se indexan aparte para que el cruce pueda afirmar cuál de las dos cosas
 * encontró.
 */
@Entity
@Table(name = "watchlist_entries", schema = "compliance")
public class WatchlistEntry {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false)
    private UUID entryId;

    /** A qué versión pertenece. Nadie consulta filas sueltas: se consulta la versión vigente. */
    @Column(name = "version_id", nullable = false, updatable = false)
    private UUID versionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false, updatable = false)
    private WatchlistKind kind;

    /** El nombre tal como lo publicó la lista. Se conserva para poder mostrárselo a quien adjudica. */
    @Column(name = "raw_name", length = 400, nullable = false, updatable = false)
    private String rawName;

    /** El nombre normalizado. Es contra éste que se cruza. */
    @Column(name = "normalized_name", length = 400, nullable = false, updatable = false)
    private String normalizedName;

    /** Los mismos tokens, alfabéticos: hace que el orden de apellidos deje de importar. */
    @Column(name = "sorted_name", length = 400, nullable = false, updatable = false)
    private String sortedName;

    @Column(name = "curp", length = 18)               private String curp;
    @Column(name = "rfc",  length = 13)               private String rfc;
    @Column(name = "date_of_birth")                   private LocalDate dateOfBirth;
    @Column(name = "country", length = 2)             private String country;

    /** El programa o fundamento bajo el que aparece: `SDN`, `69-B`, el cargo del PEP. */
    @Column(name = "program", length = 200)           private String program;

    /** Número de línea en el archivo de origen. Sin esto, revisar un rechazo es buscar a ciegas. */
    @Column(name = "source_line", nullable = false)   private int sourceLine;

    // ── Las dos llaves que permiten comparar versiones ───────────────────────────────────────
    //
    // Las publicaciones de gobierno casi nunca traen un identificador estable, así que la identidad
    // se deriva del contenido. Con las dos separadas, corregir una fecha de nacimiento sale como
    // MODIFIED en vez de como una baja y un alta.

    /** Quién es: nombre normalizado + CURP + RFC. */
    @Column(name = "identity_key", length = 64)  private String identityKey;

    /** Qué dice: todos los campos. Distinto con la misma identidad ⇒ la entrada cambió. */
    @Column(name = "content_hash", length = 64)  private String contentHash;

    // ── La raíz del CURP, para las entradas que no lo traen ──────────────────────────────────
    //
    // Se derivan DOS porque las listas no coinciden en el orden de nombre y apellidos, y adivinar
    // cuál usó cada publicador sale más caro que guardar dos cadenas de diez. Ver `CurpRoot`.
    //
    // Sólo se derivan cuando la entrada NO trae CURP: si lo trae, el cruce por documento ya es una
    // identificación y una raíz derivada encima sólo añadiría coincidencias más débiles sobre algo
    // que ya se resolvió con certeza.

    @Column(name = "curp_root_a", length = 10)   private String curpRootA;
    @Column(name = "curp_root_b", length = 10)   private String curpRootB;

    protected WatchlistEntry() { }

    public static WatchlistEntry of(UUID versionId, WatchlistKind kind, String rawName,
                                    NormalizedName normalized, String curp, String rfc,
                                    LocalDate dateOfBirth, String country, String program,
                                    int sourceLine) {
        WatchlistEntry e = new WatchlistEntry();
        e.entryId        = UUID.randomUUID();
        e.versionId      = versionId;
        e.kind           = kind;
        e.rawName        = trim(rawName, 400);
        e.normalizedName = trim(normalized.value(), 400);
        e.sortedName     = trim(normalized.sorted(), 400);
        e.curp           = blankToNull(curp);
        e.rfc            = blankToNull(rfc);
        e.dateOfBirth    = dateOfBirth;
        e.country        = blankToNull(country);
        e.program        = trim(program, 200);
        e.sourceLine     = sourceLine;
        e.identityKey    = sha256(e.normalizedName + "|" + nz(e.curp) + "|" + nz(e.rfc));
        e.contentHash    = sha256(e.identityKey + "|" + nz(String.valueOf(e.dateOfBirth))
                                  + "|" + nz(e.country) + "|" + nz(e.program) + "|" + kind);

        // La raíz NO entra en `identityKey` ni en `contentHash`: es un dato derivado de campos que
        // ya están dentro. Incluirla no aportaría nada y haría que un cambio en la derivación se
        // viera como si media lista hubiera cambiado de contenido.
        if (e.curp == null) {
            var raices = CurpRoot.candidatesFor(normalized, dateOfBirth).iterator();
            if (raices.hasNext()) e.curpRootA = raices.next();
            if (raices.hasNext()) e.curpRootB = raices.next();
        }
        return e;
    }

    private static String nz(String s) { return s == null || "null".equals(s) ? "" : s; }

    private static String sha256(String v) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(md.digest(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);   // no ocurre en una JVM estándar
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }

    public UUID getEntryId()          { return entryId; }
    public UUID getVersionId()        { return versionId; }
    public WatchlistKind getKind()    { return kind; }
    public String getRawName()        { return rawName; }
    public String getNormalizedName() { return normalizedName; }
    public String getSortedName()     { return sortedName; }
    public String getCurp()           { return curp; }
    public String getRfc()            { return rfc; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public String getCountry()        { return country; }
    public String getProgram()        { return program; }
    public int getSourceLine()        { return sourceLine; }
    public String getCurpRootA()      { return curpRootA; }
    public String getCurpRootB()      { return curpRootB; }

    /** Las raíces derivadas, sin nulos. Vacío cuando la entrada trae CURP o le falta la fecha. */
    public java.util.Set<String> curpRoots() {
        var out = new java.util.LinkedHashSet<String>();
        if (curpRootA != null) out.add(curpRootA);
        if (curpRootB != null) out.add(curpRootB);
        return out;
    }

    public String getIdentityKey()    { return identityKey; }
    public String getContentHash()    { return contentHash; }
}
