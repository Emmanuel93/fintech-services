package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.application.port.out.UnitAssignmentRepository;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.UnitAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quién se queda con la cartera de un crédito recién desembolsado.
 *
 * <p><b>Dos hechos distintos, dos dueños distintos.</b> Quién <em>originó</em> el crédito —un
 * promotor, un distribuidor, una campaña, alguien externo— es atribución de venta y paga comisión.
 * Quién <em>gestiona</em> la cartera es un ejecutivo interno y se decide al desembolsar. Confundirlos
 * atribuiría contabilidad a alguien que no pertenece a la red comercial, y repartiría carteras de
 * solicitudes que nunca llegan a existir.
 *
 * <p><b>La sucursal es intrínseca; el ejecutivo rota.</b> La sucursal se determina por geografía al
 * desembolsar y no cambia nunca: es el eje de la contabilidad, y si cambiara, la balanza de marzo
 * daría otro número en agosto. El ejecutivo sale de un round-robin dentro de esa sucursal y puede
 * cambiar cuantas veces haga falta —vacaciones, rotación, rebalanceo— sin tocar un solo asiento.
 *
 * <p><b>Nada queda sin asignar.</b> Si la sucursal no tiene ejecutivos se sube por su propia rama:
 * zona, región, nacional. Caer al primer nivel con gente conserva la responsabilidad lo más cerca
 * posible del cliente; saltar a la raíz la borra.
 */
@Service
public class PortfolioAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAssignmentService.class);

    /** Los niveles por debajo de sucursal: su gente cuenta como gente de la sucursal. */
    private static final List<String> NIVELES_HOJA = List.of("EXECUTIVE", "DISTRIBUTOR");

    private final OrgUnitRepository unitRepository;
    private final OrgLevelRepository levelRepository;
    private final UnitAssignmentRepository assignmentRepository;

    public PortfolioAssignmentService(OrgUnitRepository unitRepository,
                                       OrgLevelRepository levelRepository,
                                       UnitAssignmentRepository assignmentRepository) {
        this.unitRepository       = unitRepository;
        this.levelRepository      = levelRepository;
        this.assignmentRepository = assignmentRepository;
    }

    /** A quién le tocó la cartera y en qué sucursal quedó atribuida. */
    public record Asignacion(UUID executiveStaffId, UUID unitId, String unitCode, int nivelEscalado) {}

    /**
     * Resuelve el dueño de una cartera para una sucursal dada.
     *
     * @param branchCode código de la sucursal que decidió la geografía (p.ej. {@code S_CUL})
     * @param semilla    reparte el round-robin; el id del crédito sirve y es estable ante reintentos
     */
    @Transactional(readOnly = true)
    public Optional<Asignacion> resolver(String branchCode, UUID semilla) {
        if (branchCode == null || branchCode.isBlank()) return Optional.empty();

        OrgUnit sucursal = unitRepository.findByCode(branchCode).orElse(null);
        if (sucursal == null) {
            log.warn("La sucursal {} no existe en el árbol comercial", branchCode);
            return Optional.empty();
        }

        List<OrgUnit> unidades = unitRepository.findAll();
        Map<UUID, OrgUnit> porId = unidades.stream()
                .collect(Collectors.toMap(OrgUnit::getUnitId, Function.identity(), (a, b) -> a));
        Map<UUID, String> nivelPorId = levelRepository.findAllOrderByDepthAsc().stream()
                .collect(Collectors.toMap(l -> l.getLevelId(), l -> l.getCode(), (a, b) -> a));

        // Escalón 0: la sucursal y sus hojas. Los ejecutivos NO cuelgan de la sucursal como
        // asignación: cada uno es un nodo propio por debajo, y su gente vive ahí. Leer sólo las
        // asignaciones de la sucursal devuelve al responsable de plaza y a nadie más.
        List<List<UUID>> escalones = new ArrayList<>();
        escalones.add(genteDeLaSucursal(sucursal, unidades, nivelPorId));

        // Y de ahí hacia arriba: zona, región, nacional.
        OrgUnit nodo = sucursal.getParentUnitId() == null ? null : porId.get(sucursal.getParentUnitId());
        int guarda = 0;
        while (nodo != null && guarda++ < 10) {   // tope contra un árbol con ciclo
            escalones.add(candidatos(nodo.getUnitId()));
            nodo = nodo.getParentUnitId() == null ? null : porId.get(nodo.getParentUnitId());
        }

        for (int nivel = 0; nivel < escalones.size(); nivel++) {
            List<UUID> gente = escalones.get(nivel);
            if (gente.isEmpty()) continue;

            // Round-robin estable: la misma semilla da el mismo ejecutivo, así que reprocesar el
            // evento no mueve la cartera de manos. Un contador en memoria no sobrevive al reinicio
            // y uno en base sería un punto de contención por una decisión que no lo merece.
            UUID elegido = gente.get(Math.floorMod(semilla.hashCode(), gente.size()));
            if (nivel > 0) {
                log.info("Cartera escalada: {} sin gente en el nivel {}, se asigna {} niveles arriba",
                        branchCode, nivel - 1, nivel);
            }
            return Optional.of(new Asignacion(elegido, sucursal.getUnitId(), branchCode, nivel));
        }

        log.error("Ni {} ni toda su rama tienen a nadie: la cartera quedaría sin dueño", branchCode);
        return Optional.empty();
    }

    /** La gente de la sucursal: la asignada a ella y la de sus nodos hoja (ejecutivos). */
    private List<UUID> genteDeLaSucursal(OrgUnit sucursal, List<OrgUnit> unidades,
                                          Map<UUID, String> nivelPorId) {
        LinkedHashSet<UUID> gente = new LinkedHashSet<>(candidatos(sucursal.getUnitId()));
        for (OrgUnit hijo : unidades) {
            if (!sucursal.getUnitId().equals(hijo.getParentUnitId())) continue;
            if (!NIVELES_HOJA.contains(nivelPorId.get(hijo.getLevelId()))) continue;
            gente.addAll(candidatos(hijo.getUnitId()));
            // `partyRef` es el enlace modelado nodo→persona; sirve de respaldo si falta la asignación.
            if (hijo.getPartyRef() != null) gente.add(hijo.getPartyRef());
        }
        return new ArrayList<>(gente);
    }

    private List<UUID> candidatos(UUID unitId) {
        return assignmentRepository.findActiveByUnit(unitId).stream()
                .filter(a -> a.getAssigneeType() == AssigneeType.STAFF)
                .map(UnitAssignment::getAssigneeId)
                .toList();
    }
}
