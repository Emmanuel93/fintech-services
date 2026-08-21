package com.fintech.party.application.port.out;

import com.fintech.party.domain.Party;
import com.fintech.party.domain.PartyStatus;
import com.fintech.party.domain.PartyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PartyRepository {
    Party save(Party party);
    Optional<Party> findById(UUID partyId);
    Optional<Party> findByProspectId(UUID prospectId);
    boolean existsByProspectId(UUID prospectId);

    /**
     * Búsqueda paginada para el backoffice (por población, no por sujeto).
     *
     * <p>Todos los filtros son opcionales y se resuelven en SQL. {@code q} es
     * texto libre contra nombre, CURP y RFC — lo que el operador recuerda del
     * cliente, no un UUID. La paginación la resuelve la base: el directorio de
     * clientes solo crece y no cabe traerlo entero para recortar en memoria.
     */
    Page<Party> search(String q, PartyType type, PartyStatus status, UUID executiveId, Pageable pageable);

    /**
     * Hidratación por lote: varios parties por id en una sola consulta.
     *
     * <p>Es lo que evita el N+1 cuando un listado de otro servicio (cartera,
     * solicitudes) tiene que pintar el nombre del obligado por fila.
     */
    List<Party> findByPartyIdIn(Collection<UUID> ids);

    /**
     * Hidratación por lote <b>por prospectId</b>.
     *
     * <p>Cartera y otros read models guardan como obligado el id del prospecto,
     * no el de la party (son entidades distintas). Este es el batch que resuelve
     * sus nombres sin una consulta por fila.
     */
    List<Party> findByProspectIdIn(Collection<UUID> prospectIds);
}
