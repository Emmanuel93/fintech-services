package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.domain.OrgLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataOrgLevelRepository extends JpaRepository<OrgLevel, UUID> {

    Optional<OrgLevel> findByCode(String code);

    Optional<OrgLevel> findByDepth(int depth);

    boolean existsByCode(String code);

    List<OrgLevel> findAllByOrderByDepthAsc();

    List<OrgLevel> findByDepthGreaterThanEqualOrderByDepthDesc(int depth);

    @Query("SELECT MAX(l.depth) FROM OrgLevel l")
    Optional<Integer> maxDepth();
}
