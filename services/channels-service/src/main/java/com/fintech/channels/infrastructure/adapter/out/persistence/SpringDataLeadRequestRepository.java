package com.fintech.channels.infrastructure.adapter.out.persistence;

import com.fintech.channels.domain.LeadRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataLeadRequestRepository extends JpaRepository<LeadRequest, UUID> {}
