package com.fintech.invoicing.infrastructure.adapter.in.messaging;

import com.fintech.invoicing.application.service.FiscalProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FiscalProfileUpdatedListener {

    private static final Logger log = LoggerFactory.getLogger(FiscalProfileUpdatedListener.class);
    private final FiscalProfileService fiscalProfileService;

    public FiscalProfileUpdatedListener(FiscalProfileService fiscalProfileService) {
        this.fiscalProfileService = fiscalProfileService;
    }

    @KafkaListener(topics = "party.fiscal-profile-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "fiscalProfileUpdatedListenerContainerFactory")
    public void onMessage(FiscalProfileUpdatedPayload p) {
        log.debug("fiscal-profile-updated partyId={} regime={}", p.partyId(), p.taxRegime());
        fiscalProfileService.onFiscalProfileUpdated(p.partyId(), p.prospectId(), p.partyType(), p.rfc(),
                p.taxName(), p.taxRegime(), p.taxZipCode(), p.cfdiUse());
    }
}
