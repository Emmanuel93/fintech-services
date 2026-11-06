package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.InstallmentUpcomingEvent;
import com.fintech.creditportfolio.infrastructure.config.CreditPortfolioProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import com.fintech.shared.time.BusinessClock;

/**
 * Busca las cuotas PENDING que vencen dentro de reminderLeadDays (T5) y publica InstallmentUpcoming,
 * requisito de la cobranza temprana (EC-01).
 *
 * <p>Sin cron propio: lo corre {@link DayCloseRunner} en el cierre del día, después de la mora.
 */
@Component
public class UpcomingInstallmentJob {


    private static final Logger log = LoggerFactory.getLogger(UpcomingInstallmentJob.class);

    private final InstallmentRepository installmentRepository;
    private final CreditPortfolioEventPublisher eventPublisher;
    private final CreditPortfolioProperties properties;
    private final BusinessClock businessClock;

    public UpcomingInstallmentJob(InstallmentRepository installmentRepository,
                                   CreditPortfolioEventPublisher eventPublisher,
                                   CreditPortfolioProperties properties,
                                  BusinessClock businessClock) {
        this.businessClock = businessClock;
        this.installmentRepository = installmentRepository;
        this.eventPublisher        = eventPublisher;
        this.properties            = properties;
    }

    @Transactional(readOnly = true)
    public void run() {
        runFor(businessClock.today());
    }

    /** El trabajo para un «hoy» explícito, sin candado: lo usa el soporte de pruebas (ver InstallmentDueJob). */
    public void runFor(LocalDate today) {
        LocalDate target = today.plusDays(properties.getReminderLeadDays());
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
