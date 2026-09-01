package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.relief.ReliefProgram;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataReliefProgramRepository extends JpaRepository<ReliefProgram, UUID> {
}
