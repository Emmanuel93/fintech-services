package com.fintech.party.application.port.out;

import com.fintech.party.domain.KycVerification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycVerificationRepository {
    KycVerification save(KycVerification verification);
    Optional<KycVerification> findById(UUID verificationId);
    Optional<KycVerification> findLatestByPartyIdAndDocumentType(UUID partyId, String documentType);
    List<KycVerification> findAllByPartyId(UUID partyId);
}
