package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.InstallmentUpcomingEvent;
import com.fintech.creditportfolio.infrastructure.config.CreditPortfolioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Nightly at 00:00 (just before InstallmentDueJob at 00:01) — finds PENDING installments due
 * reminderLeadDays (T5) from now and publishes InstallmentUpcoming, prerequisite for
 * Collections' early collections (EC-01).
 */
@Component
public class UpcomingInstallmentJob {

    private static final Logger log = LoggerFactory.getLogger(UpcomingInstallmentJob.class);

    private final InstallmentRepository installmentRepository;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final CreditPortfolioProperties properties;

    public UpcomingInstallmentJob(InstallmentRepository installmentRepository,
                                   CreditPortfolioEventPublisher eventPublisher,
                                   CreditPortfolioProperties properties) {
        this.installmentRepository = installmentRepository;
        this.eventPublisher        = eventPublisher;
        this.properties            = properties;
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional(readOnly = true)
    public void run() {
        LocalDate target = LocalDate.now().plusDays(properties.getReminderLeadDays());
        List<Installment> upcoming = installmentRepository.findDueOn(target);
        log.info("UpcomingInstallmentJob starting installments={} targetDate={}", upcoming.size(), target);

        for (Installment installment : upcoming) {
            try {
                eventPublisher.publishInstallmentUpcoming(new InstallmentUpcomingEvent(
                        installment.getInstallmentId(),
                        installment.getScheduleId(),       // scheduleId == creditAccountId
                        installment.getDueDate(),
                        installment.getTotalAmount()));
            } catch (Exception ex) {
                log.error("UpcomingInstallmentJob failed installmentId={}: {}",
                        installment.getInstallmentId(), ex.getMessage(), ex);
            }
        }
        log.info("UpcomingInstallmentJob finished published={} targetDate={}", upcoming.size(), target);
    }
}
