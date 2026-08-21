package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.application.port.out.StaffUserRepository;
import com.fintech.identity.domain.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JpaStaffUserRepository
        extends JpaRepository<StaffUser, UUID>, StaffUserRepository {

    @Override
    Optional<StaffUser> findByEmail(String email);

    @Override
    boolean existsByEmail(String email);
}
