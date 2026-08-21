package com.fintech.accounting.application.port.out;

import com.fintech.accounting.domain.PostingRule;

import java.util.Optional;

public interface PostingRuleRepository {
    Optional<PostingRule> findByTriggerEvent(String triggerEvent);
}
