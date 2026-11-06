package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.InstallmentDueEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import com.fintech.shared.time.BusinessClock;

/**
 * Busca las mensualidades pendientes que vencen hoy <b>o que ya vencieron</b> y publica
 * InstallmentDue para que notificaciones y cobranza reaccionen. Sin cron propio: lo corre
 * {@link DayCloseRunner} en el cierre del día, con la fecha del día que empieza.
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
    private final BusinessClock businessClock;

    public InstallmentDueJob(InstallmentRepository installmentRepository,
                              CreditPortfolioEventPublisher eventPublisher,
                             BusinessClock businessClock) {
        this.businessClock = businessClock;
        this.installmentRepository = installmentRepository;
        this.eventPublisher        = eventPublisher;
    }

    @Transactional(readOnly = true)
    public void run() {
        runFor(businessClock.today());
    }

    /**
     * El trabajo del job para una fecha explícita, <b>sin candado</b>: lo usa el soporte de pruebas,
     * que lo dispara varias veces seguidas al correr el reloj. El candado es para el cron, que
     * corre en cada réplica; una llamada explícita ya es una sola.
     */
    public void runFor(LocalDate today) {
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
