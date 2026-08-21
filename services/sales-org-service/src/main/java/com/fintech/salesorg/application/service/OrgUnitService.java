package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.in.CreateOrgUnitCommand;
import com.fintech.salesorg.application.port.in.OrgUnitUseCase;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.domain.DuplicateCodeException;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.OrgLevel;
import com.fintech.salesorg.domain.OrgLevelNotFoundException;
import com.fintech.salesorg.domain.OrgUnit;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrgUnitService implements OrgUnitUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrgUnitService.class);

    private final OrgUnitRepository unitRepository;
    private final OrgLevelRepository levelRepository;

    public OrgUnitService(OrgUnitRepository unitRepository, OrgLevelRepository levelRepository) {
        this.unitRepository = unitRepository;
        this.levelRepository = levelRepository;
    }

    @Override
    public OrgUnit create(CreateOrgUnitCommand cmd) {
        if (unitRepository.existsByCode(cmd.code())) {
            throw new DuplicateCodeException(cmd.code());
        }
        OrgLevel level = levelRepository.findById(cmd.levelId())
                .orElseThrow(() -> new OrgLevelNotFoundException(String.valueOf(cmd.levelId())));

        String path;
        if (cmd.parentUnitId() == null) {
            // Unidad raíz: solo el nivel de menor profundidad (0) puede ir sin padre.
            if (level.getDepth() != 0) {
                throw new InvalidHierarchyException(
                        "Una unidad sin padre debe ser del nivel raíz (profundidad 0), no de "
                        + level.getCode());
            }
            path = cmd.code();
        } else {
            OrgUnit parent = unitRepository.findById(cmd.parentUnitId())
                    .orElseThrow(() -> new OrgUnitNotFoundException(String.valueOf(cmd.parentUnitId())));
            OrgLevel parentLevel = levelRepository.findById(parent.getLevelId())
                    .orElseThrow(() -> new OrgLevelNotFoundException(String.valueOf(parent.getLevelId())));
            // El hijo debe estar exactamente un escalón por debajo del padre.
            if (parentLevel.getDepth() != level.getDepth() - 1) {
                throw new InvalidHierarchyException(
                        "El nivel " + level.getCode() + " (profundidad " + level.getDepth() + ") no "
                        + "cuelga de " + parentLevel.getCode() + " (profundidad " + parentLevel.getDepth() + ")");
            }
            path = parent.getPath() + "." + cmd.code();
        }

        OrgUnit unit = OrgUnit.create(cmd.levelId(), cmd.parentUnitId(), cmd.code(), cmd.name(),
                path, cmd.partyRef(), cmd.createdBy());
        log.info("Creating org unit code={} path={} level={}", cmd.code(), path, level.getCode());
        return unitRepository.save(unit);
    }

    /**
     * Cuelga una unidad —y toda su rama— de otro padre.
     *
     * <p>Reorganizar la fuerza de ventas es una operación normal: una sucursal cambia de zona, una
     * zona pasa a otra región. Sin esto sólo quedaba borrar y recrear, y eso pierde las
     * asignaciones y la historia de quién estuvo dónde.
     *
     * <p>Se reescribe el {@code path} de la unidad y el de todos sus descendientes, porque es una
     * ruta materializada: dejar los hijos con la ruta vieja los desconectaría del árbol sin que
     * ninguna consulta fallara —seguirían existiendo, colgando de un ancestro que ya no es el suyo—.
     */
    @Override
    @Transactional
    public OrgUnit move(UUID unitId, UUID nuevoPadreId) {
        OrgUnit unit = unitRepository.findById(unitId)
                .orElseThrow(() -> new OrgUnitNotFoundException(String.valueOf(unitId)));
        OrgLevel level = levelRepository.findById(unit.getLevelId())
                .orElseThrow(() -> new OrgLevelNotFoundException(String.valueOf(unit.getLevelId())));

        String pathAnterior = unit.getPath();
        String nuevoPath;

        if (nuevoPadreId == null) {
            if (level.getDepth() != 0) {
                throw new InvalidHierarchyException(
                        "Sólo el nivel raíz puede quedarse sin padre, y " + level.getCode() + " no lo es");
            }
            nuevoPath = unit.getCode();
        } else {
            if (nuevoPadreId.equals(unitId)) {
                throw new InvalidHierarchyException("Una unidad no puede colgar de sí misma");
            }
            OrgUnit padre = unitRepository.findById(nuevoPadreId)
                    .orElseThrow(() -> new OrgUnitNotFoundException(String.valueOf(nuevoPadreId)));
            // Mover un ancestro dentro de su propia rama dejaría un ciclo: el árbol seguiría
            // guardado y cualquier recorrido no terminaría nunca.
            if (padre.getPath().equals(pathAnterior) || padre.getPath().startsWith(pathAnterior + ".")) {
                throw new InvalidHierarchyException(
                        "No se puede mover una unidad dentro de su propia rama");
            }
            OrgLevel nivelPadre = levelRepository.findById(padre.getLevelId())
                    .orElseThrow(() -> new OrgLevelNotFoundException(String.valueOf(padre.getLevelId())));
            if (nivelPadre.getDepth() != level.getDepth() - 1) {
                throw new InvalidHierarchyException(
                        "El nivel " + level.getCode() + " (profundidad " + level.getDepth() + ") no "
                        + "cuelga de " + nivelPadre.getCode() + " (profundidad " + nivelPadre.getDepth() + ")");
            }
            nuevoPath = padre.getPath() + "." + unit.getCode();
        }

        List<OrgUnit> rama = unitRepository.findSubtree(pathAnterior);
        unit.moveTo(nuevoPadreId, nuevoPath);
        unitRepository.save(unit);

        for (OrgUnit descendiente : rama) {
            if (descendiente.getUnitId().equals(unitId)) continue;
            String suPath = descendiente.getPath();
            if (!suPath.startsWith(pathAnterior + ".")) continue;
            descendiente.moveTo(descendiente.getParentUnitId(),
                    nuevoPath + suPath.substring(pathAnterior.length()));
            unitRepository.save(descendiente);
        }

        log.info("Moved org unit {} from path {} to {} ({} descendientes reescritos)",
                unit.getCode(), pathAnterior, nuevoPath, Math.max(0, rama.size() - 1));
        return unit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgUnit> list() {
        return unitRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public OrgUnit get(UUID unitId) {
        return unitRepository.findById(unitId)
                .orElseThrow(() -> new OrgUnitNotFoundException(String.valueOf(unitId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgUnit> children(UUID unitId) {
        get(unitId); // 404 si no existe
        return unitRepository.findChildren(unitId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgUnit> subtree(UUID unitId) {
        OrgUnit unit = get(unitId);
        return unitRepository.findSubtree(unit.getPath());
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> distributorsInSubtree(UUID unitId) {
        return unitRepository.findPartyRefsInSubtreeByLevel(get(unitId).getPath(), "DISTRIBUTOR");
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> executivesInSubtree(UUID unitId) {
        return unitRepository.findPartyRefsInSubtreeByLevel(get(unitId).getPath(), "EXECUTIVE");
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<UUID> resolveDistributorByCode(String code) {
        return unitRepository.findPartyRefByCodeAndLevel(code, "DISTRIBUTOR");
    }
}
