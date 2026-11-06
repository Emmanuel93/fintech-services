package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.application.port.out.BeneficiaryFlagRepository;
import com.fintech.creditportfolio.domain.beneficiary.BeneficiaryFlag;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaBeneficiaryFlagAdapter implements BeneficiaryFlagRepository {

    private final SpringDataBeneficiaryFlagRepository jpa;

    JpaBeneficiaryFlagAdapter(SpringDataBeneficiaryFlagRepository jpa) {
        this.jpa = jpa;
    }

    @Override public BeneficiaryFlag save(BeneficiaryFlag flag)                        { return jpa.save(flag); }
    @Override public Optional<BeneficiaryFlag> findById(UUID flagId)                   { return jpa.findById(flagId); }
    @Override public List<BeneficiaryFlag> findActiveByBeneficiary(UUID beneficiary)   { return jpa.findByBeneficiaryPartyIdAndClearedAtIsNullOrderByRaisedAtAsc(beneficiary); }
    @Override public List<BeneficiaryFlag> findByBeneficiary(UUID beneficiary)         { return jpa.findByBeneficiaryPartyIdOrderByRaisedAtDesc(beneficiary); }
}
