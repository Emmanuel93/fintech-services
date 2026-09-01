package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.AccountCloseProfileRepository;
import com.fintech.closing.domain.AccountCloseProfile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAccountCloseProfileAdapter implements AccountCloseProfileRepository {

    private final SpringDataAccountCloseProfileRepository jpa;

    JpaAccountCloseProfileAdapter(SpringDataAccountCloseProfileRepository jpa) { this.jpa = jpa; }

    @Override public Optional<AccountCloseProfile> findById(UUID id) { return jpa.findById(id); }
    @Override public AccountCloseProfile save(AccountCloseProfile p)  { return jpa.save(p); }
    @Override public long countActive()                               { return jpa.countActive(); }

    @Override
    public List<AccountCloseProfile> findActiveForClosing(LocalDate businessDate, int limit) {
        return jpa.findActive(PageRequest.of(0, Math.max(limit, 1)));
    }
}
