package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.in.OrgLevelUseCase;
import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.domain.DuplicateCodeException;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.OrgLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class OrgLevelService implements OrgLevelUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrgLevelService.class);

    private final OrgLevelRepository levelRepository;
    private final OrgUnitRepository unitRepository;

    public OrgLevelService(OrgLevelRepository levelRepository, OrgUnitRepository unitRepository) {
        this.levelRepository = levelRepository;
        this.unitRepository = unitRepository;
    }

    @Override
    public OrgLevel append(String code, String name) {
        requireCodeFree(code);
        int depth = levelRepository.maxDepth().map(d -> d + 1).orElse(0);
        log.info("Appending org level code={} at depth={}", code, depth);
        return levelRepository.save(OrgLevel.create(code, name, depth));
    }

    @Override
    public OrgLevel insertAt(int depth, String code, String name) {
        if (depth < 0) {
            throw new InvalidHierarchyException("La profundidad no puede ser negativa");
        }
        requireCodeFree(code);

        // Correr un nivel intermedio con unidades ya colgando exigiría reconstruir sus paths: eso es
        // trabajo del job de reasignación, no de un alta de nivel. Aquí se rechaza con un mensaje claro.
        List<OrgLevel> toShift = levelRepository.findFromDepthDescending(depth);
        boolean occupied = toShift.stream().anyMatch(l -> unitRepository.existsByLevelId(l.getLevelId()));
        if (occupied) {
            throw new InvalidHierarchyException(
                    "Hay unidades en la posición " + depth + " o más profundas; insertar un nivel "
                    + "intermedio ahí requiere reasignar el árbol");
        }

        // Del más profundo al menos profundo: cada nivel se mueve a un slot ya libre, sin colisión.
        toShift.forEach(l -> {
            l.shiftDepthBy(1);
            levelRepository.save(l);
        });
        log.info("Inserting org level code={} at depth={} (shifted {} levels)", code, depth, toShift.size());
        return levelRepository.save(OrgLevel.create(code, name, depth));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrgLevel> list() {
        return levelRepository.findAllOrderByDepthAsc();
    }

    private void requireCodeFree(String code) {
        if (levelRepository.existsByCode(code)) {
            throw new DuplicateCodeException(code);
        }
    }
}
