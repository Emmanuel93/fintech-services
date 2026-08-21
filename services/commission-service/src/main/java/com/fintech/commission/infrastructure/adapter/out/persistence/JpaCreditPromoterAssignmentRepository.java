package com.fintech.commission.infrastructure.adapter.out.persistence;

import com.fintech.commission.application.port.out.CreditPromoterAssignmentRepository;
import com.fintech.commission.domain.CreditPromoterAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaCreditPromoterAssignmentRepository
        extends JpaRepository<CreditPromoterAssignment, UUID>, CreditPromoterAssignmentRepository {
}
