package com.fintech.party.infrastructure.adapter.out.persistence;

import com.fintech.party.application.port.out.KycVerificationRepository;
import com.fintech.party.domain.KycVerification;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JpaKycVerificationAdapter implements KycVerificationRepository {

    private final SpringDataKycVerificationRepository repository;

    public JpaKycVerificationAdapter(SpringDataKycVerificationRepository repository) {
        this.repository = repository;
    }

    @Override
    public KycVerification save(KycVerification verification) {
        return repository.save(verification);
    }

    @Override
    public Optional<KycVerification> findById(UUID verificationId) {
        return repository.findById(verificationId);
    }

    @Override
    public Optional<KycVerification> findLatestByPartyIdAndDocumentType(UUID partyId, String documentType) {
        return repository.findLatestByPartyIdAndDocumentType(partyId, documentType);
    }

    @Override
    public List<KycVerification> findAllByPartyId(UUID partyId) {
        return repository.findAllByPartyId(partyId);
    }
}
