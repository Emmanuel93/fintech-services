package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.InstallmentDueEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Diario a las 00:01 — busca las mensualidades pendientes que vencen hoy
 * <b>o que ya vencieron</b> y publica InstallmentDue para que notificaciones y
 * cobranza reaccionen.
 *
 * <p>Antes sólo miraba "vence hoy". Una corrida fallida, o una fecha movida,
 * dejaba esa mensualidad PENDING para siempre: nadie la marcaba vencida y el
 * cliente no recibía aviso de que estaba en mora. Buscar "hoy o antes" hace
 * que el job sea idempotente y se recupere solo del día que se perdió.
 */
@Component
public class InstallmentDueJob {

    private static final Logger log = LoggerFactory.getLogger(InstallmentDueJob.class);

    private final InstallmentRepository installmentRepository;
    private final CreditPortfolioEventPublisher eventPublisher;

    public InstallmentDueJob(InstallmentRepository installmentRepository,
                              CreditPortfolioEventPublisher eventPublisher) {
        this.installmentRepository = installmentRepository;
        this.eventPublisher        = eventPublisher;
    }

    @Scheduled(cron = "0 1 0 * * *")
    @Transactional(readOnly = true)
    public void run() {
        LocalDate today = LocalDate.now();
        List<Installment> due = installmentRepository.findDueOnOrBefore(today);
        log.info("InstallmentDueJob starting installments={} date={}", due.size(), today);

        for (Installment installment : due) {
            try {
                eventPublisher.publishInstallmentDue(new InstallmentDueEvent(
                        installment.getInstallmentId(),
                        installment.getScheduleId(),       // scheduleId == creditAccountId
                        installment.getInstallmentNumber(),
                        installment.getDueDate(),
                        installment.getTotalAmount()));
            } catch (Exception ex) {
                log.error("InstallmentDueJob failed installmentId={}: {}",
                        installment.getInstallmentId(), ex.getMessage(), ex);
            }
        }
        log.info("InstallmentDueJob finished published={} date={}", due.size(), today);
    }
}
