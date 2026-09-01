package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.relief.ReliefEnrollment;
import com.fintech.creditportfolio.domain.relief.ReliefProgram;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReliefProgramRepository {

    ReliefProgram save(ReliefProgram programa);

    Optional<ReliefProgram> findById(UUID id);

    List<ReliefProgram> findAll();

    ReliefEnrollment saveEnrollment(ReliefEnrollment inscripcion);

    List<ReliefEnrollment> findEnrollmentsByProgram(UUID programId);

    /** Las inscripciones vigentes de una cuenta. Vacío = la cuenta no está bajo apoyo. */
    List<ReliefEnrollment> findActiveEnrollmentsByAccount(UUID creditAccountId);

    boolean existsEnrollment(UUID programId, UUID creditAccountId);
}
