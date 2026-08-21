package com.fintech.invoicing.application.service;

import com.fintech.invoicing.application.port.out.FiscalProfileRepository;
import com.fintech.invoicing.domain.FiscalProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Proyecta party.fiscal-profile-updated en el read-model local (receptor del CFDI). */
@Service
@Transactional
public class FiscalProfileService {

    private static final Logger log = LoggerFactory.getLogger(FiscalProfileService.class);
    private final FiscalProfileRepository repository;

    public FiscalProfileService(FiscalProfileRepository repository) { this.repository = repository; }

    public void onFiscalProfileUpdated(UUID partyId, UUID prospectId, String partyType, String rfc,
                                       String taxName, String taxRegime, String taxZipCode, String cfdiUse) {
        FiscalProfile profile = repository.findById(partyId).orElse(null);
        if (profile == null) {
            profile = FiscalProfile.of(partyId, prospectId, partyType, rfc, taxName, taxRegime, taxZipCode, cfdiUse);
        } else {
            profile.upsert(partyType, rfc, taxName, taxRegime, taxZipCode, cfdiUse);
            profile.linkProspect(prospectId);
        }
        repository.save(profile);
        log.info("FiscalProfile updated partyId={} regime={}", partyId, taxRegime);
    }
}
