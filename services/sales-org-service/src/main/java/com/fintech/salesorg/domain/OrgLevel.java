package com.fintech.salesorg.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Un nivel de la escalera comercial (p.ej. Región, Zona, Sucursal).
 *
 * <p>Los niveles son <b>datos configurables</b>, no un enum: se agregan o insertan desde el
 * backoffice. {@code depth} es la posición en la escalera (0 = raíz, nacional); es único —dos
 * niveles no comparten posición— y define el orden padre→hijo de las unidades.
 */
@Entity
@Table(name = "org_levels", schema = "sales_org")
public class OrgLevel {

    @Id
    @Column(name = "level_id", nullable = false, updatable = false)
    private UUID levelId;

    @Column(nullable = false)
    private int depth;

    @Column(nullable = false, length = 40, updatable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrgLevel() {}

    public static OrgLevel create(String code, String name, int depth) {
        if (depth < 0) {
            throw new InvalidHierarchyException("La profundidad de un nivel no puede ser negativa");
        }
        OrgLevel l = new OrgLevel();
        l.levelId   = UUID.randomUUID();
        l.depth     = depth;
        l.code      = code;
        l.name      = name;
        l.active    = true;
        l.createdAt = Instant.now();
        return l;
    }

    /** Corre la posición del nivel (para insertar un nivel intermedio empujando a los de abajo). */
    public void shiftDepthBy(int delta) {
        int next = this.depth + delta;
        if (next < 0) {
            throw new InvalidHierarchyException("El corrimiento dejaría un nivel en profundidad negativa");
        }
        this.depth = next;
    }

    public void rename(String name) { this.name = name; }
    public void deactivate()        { this.active = false; }

    public UUID getLevelId()     { return levelId; }
    public int getDepth()        { return depth; }
    public String getCode()      { return code; }
    public String getName()      { return name; }
    public boolean isActive()    { return active; }
    public Instant getCreatedAt(){ return createdAt; }
}
