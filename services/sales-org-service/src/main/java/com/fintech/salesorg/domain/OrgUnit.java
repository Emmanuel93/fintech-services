package com.fintech.salesorg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Una unidad del árbol comercial: un nodo concreto de la red (una sucursal, una zona, una región…).
 *
 * <p>{@code path} es la ruta materializada LTREE, con los códigos de los ancestros más el propio
 * (p.ej. {@code MX.NORTE.MTY.SUC001}). Es lo que permite pedir "todo el subárbol de X" en una sola
 * consulta indexada. El código debe ser una etiqueta LTREE válida ({@code [A-Za-z0-9_]+}) porque es
 * un segmento del path.
 */
@Entity
@Table(name = "org_units", schema = "sales_org")
public class OrgUnit {

    /** Etiqueta LTREE válida: letras, dígitos y guion bajo. Un código fuera de esto rompe el path. */
    public static final Pattern LTREE_LABEL = Pattern.compile("^[A-Za-z0-9_]+$");

    @Id
    @Column(name = "unit_id", nullable = false, updatable = false)
    private UUID unitId;

    @Column(name = "level_id", nullable = false)
    private UUID levelId;

    @Column(name = "parent_unit_id")
    private UUID parentUnitId;

    @Column(nullable = false, length = 40, updatable = false)
    private String code;

    @Column(nullable = false, length = 160)
    private String name;

    // El path se guarda como texto (Hibernate lo valida sin fricción) y la jerarquía se consulta con
    // un índice GIST funcional sobre (path::ltree). Los labels ya se validan al construir el path.
    @Column(nullable = false)
    private String path;

    @Column(nullable = false)
    private boolean active;

    // Enlace del nodo con su persona: staffUserId para un nodo EXECUTIVE, partyId del distribuidor
    // para un nodo DISTRIBUTOR. Null en las unidades estructurales (nacional…sucursal).
    @Column(name = "party_ref")
    private UUID partyRef;

    /**
     * IVA a trasladar en esta unidad y su subárbol; {@code null} hereda del padre.
     *
     * <p>La Región Fronteriza tiene estímulo fiscal y traslada una tasa menor que el resto del país,
     * así que el impuesto depende de dónde se coloca el crédito. Se declara en el nodo más alto que
     * comparta tasa —la región— y las zonas y sucursales que cuelgan la heredan: ponerla sucursal
     * por sucursal garantiza que la próxima que se abra nazca con la tasa equivocada.
     */
    @Column(name = "vat_rate", precision = 6, scale = 4)
    private java.math.BigDecimal vatRate;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrgUnit() {}

    public static OrgUnit create(UUID levelId, UUID parentUnitId, String code, String name,
                                 String path, UUID partyRef, String createdBy) {
        if (code == null || !LTREE_LABEL.matcher(code).matches()) {
            throw new InvalidHierarchyException(
                    "El código de unidad debe ser una etiqueta LTREE válida [A-Za-z0-9_]+: " + code);
        }
        OrgUnit u = new OrgUnit();
        u.unitId       = UUID.randomUUID();
        u.levelId      = levelId;
        u.parentUnitId = parentUnitId;
        u.code         = code;
        u.name         = name;
        u.path         = path;
        u.active       = true;
        u.partyRef     = partyRef;
        u.createdBy    = createdBy;
        u.createdAt    = Instant.now();
        return u;
    }

    public void rename(String name) { this.name = name; }
    public void deactivate()        { this.active = false; }

    public boolean isRoot() { return parentUnitId == null; }

    public UUID getUnitId()       { return unitId; }
    public UUID getLevelId()      { return levelId; }
    public UUID getParentUnitId() { return parentUnitId; }
    public String getCode()       { return code; }
    public String getName()       { return name; }
    /**
     * Cuelga la unidad de otro padre.
     *
     * <p>Mueve la rama entera: el {@code path} es una ruta materializada, así que cambiar de padre
     * obliga a reescribir el de todos los descendientes —lo hace el servicio, que es quien los ve—.
     * La unidad conserva su identidad y su código: reasignar una sucursal de zona no la convierte
     * en otra sucursal, y perder el id rompería sus asignaciones y su historia.
     */
    public void moveTo(UUID nuevoPadre, String nuevoPath) {
        this.parentUnitId = nuevoPadre;
        this.path = nuevoPath;
    }

    public String getPath()       { return path; }
    public boolean isActive()     { return active; }
    public java.math.BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(java.math.BigDecimal v) { this.vatRate = v; }

    public UUID getPartyRef()     { return partyRef; }
    public String getCreatedBy()  { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
