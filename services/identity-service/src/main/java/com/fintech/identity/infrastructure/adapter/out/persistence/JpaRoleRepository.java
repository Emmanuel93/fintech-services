package com.fintech.identity.infrastructure.adapter.out.persistence;

import com.fintech.identity.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JpaRoleRepository extends JpaRepository<Role, String> {

    List<Role> findByChannelOrderByCodeAsc(String channel);
}
