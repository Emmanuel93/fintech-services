package com.fintech.salesorg.infrastructure.adapter.out.persistence;

import com.fintech.salesorg.application.port.out.OrgLevelRepository;
import com.fintech.salesorg.domain.OrgLevel;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaOrgLevelAdapter implements OrgLevelRepository {

    private final SpringDataOrgLevelRepository jpa;

    JpaOrgLevelAdapter(SpringDataOrgLevelRepository jpa) {
        this.jpa = jpa;
    }

    @Override public OrgLevel save(OrgLevel level)              { return jpa.save(level); }
    @Override public Optional<OrgLevel> findById(UUID levelId)  { return jpa.findById(levelId); }
    @Override public Optional<OrgLevel> findByCode(String code) { return jpa.findByCode(code); }
    @Override public Optional<OrgLevel> findByDepth(int depth)  { return jpa.findByDepth(depth); }
    @Override public boolean existsByCode(String code)          { return jpa.existsByCode(code); }

    @Override
    public List<OrgLevel> findAllOrderByDepthAsc() {
        return jpa.findAllByOrderByDepthAsc();
    }

    @Override
    public List<OrgLevel> findFromDepthDescending(int depth) {
        return jpa.findByDepthGreaterThanEqualOrderByDepthDesc(depth);
    }

    @Override
    public Optional<Integer> maxDepth() {
        return jpa.maxDepth();
    }
}
