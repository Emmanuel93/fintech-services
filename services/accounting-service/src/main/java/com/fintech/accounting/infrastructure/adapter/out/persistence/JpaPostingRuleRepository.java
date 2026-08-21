package com.fintech.accounting.infrastructure.adapter.out.persistence;

import com.fintech.accounting.application.port.out.PostingRuleRepository;
import com.fintech.accounting.domain.PostingRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaPostingRuleRepository extends JpaRepository<PostingRule, String>, PostingRuleRepository {
    @Override
    Optional<PostingRule> findByTriggerEvent(String triggerEvent);
}
