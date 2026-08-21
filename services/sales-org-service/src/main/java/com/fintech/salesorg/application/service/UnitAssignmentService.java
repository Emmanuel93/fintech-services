package com.fintech.salesorg.application.service;

import com.fintech.salesorg.application.port.in.AssignToUnitCommand;
import com.fintech.salesorg.application.port.in.ManageAssignmentsUseCase;
import com.fintech.salesorg.application.port.out.OrgUnitRepository;
import com.fintech.salesorg.application.port.out.UnitAssignmentRepository;
import com.fintech.salesorg.domain.AssigneeType;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
import com.fintech.salesorg.domain.UnitAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UnitAssignmentService implements ManageAssignmentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(UnitAssignmentService.class);

    private final UnitAssignmentRepository assignmentRepository;
    private final OrgUnitRepository unitRepository;

    public UnitAssignmentService(UnitAssignmentRepository assignmentRepository,
                                 OrgUnitRepository unitRepository) {
        this.assignmentRepository = assignmentRepository;
        this.unitRepository = unitRepository;
    }

    @Override
    public UnitAssignment assign(AssignToUnitCommand cmd) {
        if (unitRepository.findById(cmd.unitId()).isEmpty()) {
            throw new OrgUnitNotFoundException(String.valueOf(cmd.unitId()));
        }
        // Cerrar la vigente PRIMERO (update inmediato) y luego insertar la nueva: así el índice único
        // parcial "una activa por asignado" nunca ve dos activas, sin depender del orden de flush.
        int ended = assignmentRepository.endActive(cmd.assigneeType(), cmd.assigneeId(), Instant.now());
        if (ended > 0) {
            log.info("Reassigning {} {}: closed {} prior active assignment(s)",
                    cmd.assigneeType(), cmd.assigneeId(), ended);
        }
        UnitAssignment saved = assignmentRepository.save(UnitAssignment.create(
                cmd.unitId(), cmd.assigneeType(), cmd.assigneeId(), cmd.assignmentRole(), cmd.assignedBy()));
        log.info("Assigned {} {} to unit {} role={}",
                cmd.assigneeType(), cmd.assigneeId(), cmd.unitId(), saved.getAssignmentRole());
        return saved;
    }

    @Override
    public int endAssignment(AssigneeType assigneeType, UUID assigneeId) {
        return assignmentRepository.endActive(assigneeType, assigneeId, Instant.now());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UnitAssignment> currentAssignment(AssigneeType assigneeType, UUID assigneeId) {
        return assignmentRepository.findActiveByAssignee(assigneeType, assigneeId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitAssignment> activeInUnit(UUID unitId) {
        return assignmentRepository.findActiveByUnit(unitId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitAssignment> scopeOfUnit(UUID unitId) {
        String path = unitRepository.findById(unitId)
                .orElseThrow(() -> new OrgUnitNotFoundException(String.valueOf(unitId)))
                .getPath();
        return assignmentRepository.findActiveInSubtree(path);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UnitAssignment> history(AssigneeType assigneeType, UUID assigneeId) {
        return assignmentRepository.findHistoryByAssignee(assigneeType, assigneeId);
    }
}
