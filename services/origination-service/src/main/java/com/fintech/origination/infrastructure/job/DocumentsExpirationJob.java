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
 * Barrido horario del TTL de documentos (E6): las solicitudes en PENDING_DOCUMENTS cuyo plazo venció
 * se cancelan por abandono (PENDING_DOCUMENTS → CANCELLED).
 *
 * <p>Cada solicitud se procesa en su propia transacción para que un fallo aislado no tumbe al resto.
 * Vive en origination (no en notifications): no viola la regla de "sin cron en notifications".
 */
@Component
public class DocumentsExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentsExpirationJob.class);

    private final CreditApplicationRepository applicationRepository;

    public DocumentsExpirationJob(CreditApplicationRepository applicationRepository) {
        this.applicationRepository = applicationRepository;
    }

    @Scheduled(cron = "0 30 * * * *")   // a la media de cada hora (desfasado de OfferExpirationJob)
    public void expireStaleDocumentRequests() {
        Instant now = Instant.now();
        List<CreditApplication> expired = applicationRepository.findPendingDocumentsPastDeadline(now);
        if (expired.isEmpty()) {
            return;
        }
        log.info("DocumentsExpirationJob: {} application(s) past documents deadline", expired.size());
        for (CreditApplication app : expired) {
            expireSingle(app);
        }
    }

    @Transactional
    public void expireSingle(CreditApplication app) {
        try {
            app.expireForMissingDocuments();
            applicationRepository.save(app);
            log.info("Application cancelled for missing documents applicationId={}", app.getApplicationId());
        } catch (Exception ex) {
            log.error("Failed to expire documents applicationId={}: {}",
                    app.getApplicationId(), ex.getMessage());
        }
    }
}
