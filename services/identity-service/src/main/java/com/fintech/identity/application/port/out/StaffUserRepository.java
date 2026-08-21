package com.fintech.identity.application.port.out;

import com.fintech.identity.domain.StaffUser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffUserRepository {

    Optional<StaffUser> findByEmail(String email);

    Optional<StaffUser> findById(UUID staffUserId);

    boolean existsByEmail(String email);

    StaffUser save(StaffUser user);

    List<StaffUser> findAll();
}
