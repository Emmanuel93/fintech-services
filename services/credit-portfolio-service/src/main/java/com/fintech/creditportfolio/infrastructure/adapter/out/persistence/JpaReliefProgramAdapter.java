package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.ReliefProgramRepository;
import com.fintech.creditportfolio.domain.relief.ReliefEnrollment;
import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaReliefProgramAdapter implements ReliefProgramRepository {

    private final SpringDataReliefProgramRepository programas;
    private final SpringDataReliefEnrollmentRepository inscripciones;

    JpaReliefProgramAdapter(SpringDataReliefProgramRepository programas,
                            SpringDataReliefEnrollmentRepository inscripciones) {
        this.programas     = programas;
        this.inscripciones = inscripciones;
    }

    @Override public ReliefProgram save(ReliefProgram p)          { return programas.save(p); }
    @Override public Optional<ReliefProgram> findById(UUID id)    { return programas.findById(id); }
    @Override public List<ReliefProgram> findAll()                { return programas.findAll(); }

    @Override public ReliefEnrollment saveEnrollment(ReliefEnrollment e) { return inscripciones.save(e); }

    @Override public List<ReliefEnrollment> findEnrollmentsByProgram(UUID programId) {
        return inscripciones.findByReliefProgramId(programId);
    }

    @Override public List<ReliefEnrollment> findActiveEnrollmentsByAccount(UUID creditAccountId) {
        return inscripciones.findByCreditAccountIdAndReleasedAtIsNull(creditAccountId);
    }

    @Override public boolean existsEnrollment(UUID programId, UUID creditAccountId) {
        return inscripciones.existsByReliefProgramIdAndCreditAccountId(programId, creditAccountId);
    }
}
