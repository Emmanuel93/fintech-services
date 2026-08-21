package com.fintech.origination.infrastructure.job;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.CreditApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Hourly job that expires offers whose {@code validUntil} has passed (OM-04).
 *
 * Transitions OFFER_PRESENTED → OFFER_EXPIRED.
 * Each application is processed in its own transaction so a single failure
 * does not roll back the others.
 */
@Component
public class OfferExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(OfferExpirationJob.class);

    private final CreditApplicationRepository applicationRepository;

    public OfferExpirationJob(CreditApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Scheduled(cron = "0 0 * * * *")   // top of every hour
    public void expireStaleOffers() {
        Instant now = Instant.now();
        List<CreditApplication> expired = applicationRepository.findExpiredOffers(now);
        if (expired.isEmpty()) {
            return;
        }
        log.info("OfferExpirationJob: {} offer(s) to expire", expired.size());
        for (CreditApplication app : expired) {
            expireSingle(app);
        }
    }

    @Transactional
    public void expireSingle(CreditApplication app) {
        try {
            app.expireOffer();
            applicationRepository.save(app);
            log.info("Offer expired applicationId={}", app.getApplicationId());
        } catch (Exception ex) {
            log.error("Failed to expire offer applicationId={}: {}", app.getApplicationId(), ex.getMessage());
        }
    }
}
