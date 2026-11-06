package com.fintech.commission.application.service;

import com.fintech.commission.application.port.out.BeneficiaryProfileRepository;
import com.fintech.commission.domain.BeneficiaryProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who commissions are paid to: the fiscal profile party publishes, and the account operations registers for the lots'
 * SPEI. A beneficiary is known to commission by the id the loans carry as promoter, which may be the party's or its
 * prospect's: the profile is kept under both.
 */
@Service
@Transactional
public class BeneficiaryProfileService {

    private static final Logger log = LoggerFactory.getLogger(BeneficiaryProfileService.class);

    private final BeneficiaryProfileRepository profiles;

    public BeneficiaryProfileService(BeneficiaryProfileRepository profiles) {
        this.profiles = profiles;
    }

    public void onFiscalProfileUpdated(UUID partyId, UUID prospectId, String partyType, String rfc, String taxName,
                                       String taxRegime) {
        for (UUID id : idsOf(partyId, prospectId)) {
            BeneficiaryProfile p = profiles.findById(id).orElseGet(() -> BeneficiaryProfile.of(id));
            p.updateFiscal(rfc, partyType, taxName, taxRegime);
            profiles.save(p);
        }
        log.debug("Perfil fiscal de beneficiaria partyId={} prospectId={} régimen={}", partyId, prospectId, taxRegime);
    }

    /** The account the beneficiary's lots are paid to. Lots waiting for it are requested in the next run. */
    public BeneficiaryProfile registerPayoutAccount(UUID partyId, String clabe, String holder) {
        BeneficiaryProfile p = profiles.findById(partyId).orElseGet(() -> BeneficiaryProfile.of(partyId));
        p.updatePayoutAccount(clabe, holder);
        BeneficiaryProfile saved = profiles.save(p);
        log.info("Cuenta de pago de comisiones registrada beneficiaria={} titular={}", partyId, saved.getPayoutHolder());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<BeneficiaryProfile> find(UUID partyId) {
        return profiles.findById(partyId);
    }

    private static List<UUID> idsOf(UUID partyId, UUID prospectId) {
        if (prospectId == null || prospectId.equals(partyId)) return List.of(partyId);
        return List.of(partyId, prospectId);
    }
}
