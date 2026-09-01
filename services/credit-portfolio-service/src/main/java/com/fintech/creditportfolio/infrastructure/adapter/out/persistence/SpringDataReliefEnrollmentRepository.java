package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.relief.ReliefEnrollment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataReliefEnrollmentRepository extends JpaRepository<ReliefEnrollment, UUID> {

    List<ReliefEnrollment> findByReliefProgramId(UUID reliefProgramId);

    List<ReliefEnrollment> findByCreditAccountIdAndReleasedAtIsNull(UUID creditAccountId);

    boolean existsByReliefProgramIdAndCreditAccountId(UUID reliefProgramId, UUID creditAccountId);
}
