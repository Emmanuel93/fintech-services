package com.fintech.identity.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Un rol del backoffice y lo que puede hacer.
 *
 * <p>La capacidad es un texto y no un enum a propósito: cada módulo nuevo trae las suyas
 * ({@code salesorg.view} llegó con la estructura comercial) y obligar a un despliegue de identity
 * para dar de alta una capacidad de otro servicio convierte a identity en el cuello de botella de
 * todos los demás. Identity guarda quién puede qué; qué significa cada capacidad lo sabe el canal
 * que la aplica.
 *
 * <p>{@code systemManaged} distingue los roles que define el producto. Se les pueden mover
 * capacidades —para eso existe la pantalla— pero no se borran: quitar ADMIN dejaría la instalación
 * sin quién la administre y sin forma de repararlo desde la consola.
 */
@Entity
@Table(name = "roles", schema = "identity")
public class Role {

    @Id
    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "channel", nullable = false, length = 30)
    private String channel = "BACKOFFICE";

    @Column(name = "system_managed", nullable = false)
    private boolean systemManaged = true;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "role_capabilities",
            schema = "identity",
            joinColumns = @JoinColumn(name = "role_code"))
    @Column(name = "capability", nullable = false, length = 80)
    private Set<String> capabilities = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Role() {}

    /**
     * Reemplaza el conjunto completo de capacidades.
     *
     * <p>Reemplazo y no altas/bajas sueltas: la pantalla edita una matriz y manda el estado al
     * que quiere llegar. Con operaciones incrementales, dos personas editando el mismo rol
     * terminan con la unión de sus dos intenciones y nadie pidió eso.
     */
    public void replaceCapabilities(Set<String> nuevas) {
        capabilities.clear();
        capabilities.addAll(nuevas);
        updatedAt = Instant.now();
    }

    public String getCode()             { return code; }
    public String getName()             { return name; }
    public String getDescription()      { return description; }
    public String getChannel()          { return channel; }
    public boolean isSystemManaged()    { return systemManaged; }
    public Set<String> getCapabilities() { return Set.copyOf(capabilities); }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getUpdatedAt()       { return updatedAt; }
}
