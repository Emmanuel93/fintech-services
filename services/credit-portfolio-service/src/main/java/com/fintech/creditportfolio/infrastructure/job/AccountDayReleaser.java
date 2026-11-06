package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.AccountReleaseRepository;
import com.fintech.creditportfolio.application.port.out.AccountReleaseRepository.Release;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.ChargesTotals;
import com.fintech.creditportfolio.application.port.out.DayCloseRepository.DayClose;
import com.fintech.creditportfolio.domain.event.AccountDayClosedEvent;
import com.fintech.shared.kafka.ConsumerCatchUp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.fintech.shared.time.BusinessClock;

/**
 * Suelta, cuenta por cuenta, las que cargos dejó fuera del cierre de su día y después devengaron.
 *
 * <p>Es el cierre de cartera en pequeño, con las mismas compuertas: el día ya cerró aquí, cartera consumió
 * todo lo que cargos publicó, y los intereses de la cuenta en ese lote —aplicados más rechazados— son los
 * que cargos dijo. Entonces avisa {@code credit-portfolio.account-day-closed}, después de que el broker
 * confirmó todo lo publicado antes (los saldos de esa cuenta), y closing corta la cuenta.
 */
@Component
public class AccountDayReleaser {

    private static final Logger log = LoggerFactory.getLogger(AccountDayReleaser.class);
    private static final int PAGE = 500;

    private final AccountReleaseRepository releases;
    private final DayCloseRepository dayCloses;
    private final CreditPortfolioEventPublisher publisher;
    private final ConsumerCatchUp catchUp;
    private final Duration gateWait;
    private final Duration gatePoll;
    private final String consumerGroup;

    public AccountDayReleaser(AccountReleaseRepository releases, DayCloseRepository dayCloses,
                              CreditPortfolioEventPublisher publisher, ConsumerCatchUp catchUp,
                              @Value("${fintech.day-close.gate-wait:PT10M}") Duration gateWait,
                              @Value("${fintech.day-close.gate-poll:PT2S}") Duration gatePoll,
                              @Value("${spring.kafka.consumer.group-id:credit-portfolio-service}") String consumerGroup) {
        this.releases      = releases;
        this.dayCloses     = dayCloses;
        this.publisher     = publisher;
        this.catchUp       = catchUp;
        this.gateWait      = gateWait;
        this.gatePoll      = gatePoll;
        this.consumerGroup = consumerGroup;
    }

    /** Cargos avisó que la cuenta devengó su día. Se anota y se intenta soltar. */
    public boolean receive(LocalDate businessDate, UUID creditAccountId, ChargesTotals totals) {
        releases.record(businessDate, creditAccountId, totals);
        return release(businessDate, creditAccountId);
    }

    /** Las que esperan, en orden. La corren la reanudación y el cierre del día. */
    public int releasePending() {
        int soltadas = 0;
        for (Release r : releases.pending(PAGE)) {
            if (release(r.businessDate(), r.creditAccountId())) soltadas++;
        }
        return soltadas;
    }

    /** @return si la cuenta quedó soltada */
    public boolean release(LocalDate businessDate, UUID creditAccountId) {
        Release r = releases.find(businessDate, creditAccountId).orElse(null);
        if (r == null) return false;

        if (dayCloses.find(businessDate).filter(DayClose::done).isEmpty()) {
            return wait(r, "cartera no ha cerrado el " + businessDate);
        }
        var consumo = catchUp.await(consumerGroup, DayCloseRunner.CHARGES_TOPICS, gateWait, gatePoll);
        if (!consumo.caughtUp()) {
            return wait(r, "faltan " + consumo.pending() + " cargos de charges por consumir");
        }
        ChargesTotals aplicado = dayCloses.accountInterest(businessDate, creditAccountId);
        if (!aplicado.matches(r.totals())) {
            return wait(r, String.format("los intereses de la cuenta no cuadran: cargos avisó %d ordinarios y %d"
                    + " moratorios; cartera tiene %d y %d", r.totals().ordinaryCharges(), r.totals().moratoriumCharges(),
                    aplicado.ordinaryCharges(), aplicado.moratoriumCharges()));
        }

        // Una cuenta ya soltada se vuelve a avisar sin costo: closing es idempotente.
        publisher.publishAccountDayClosed(new AccountDayClosedEvent(businessDate, creditAccountId, BusinessClock.current().now()));
        if (!r.released()) {
            releases.released(businessDate, creditAccountId);
            log.info("Cuenta {} soltada a closing: su interés del {} ya está aplicado y cuadra", creditAccountId, businessDate);
        }
        return true;
    }

    private boolean wait(Release r, String reason) {
        releases.waiting(r.businessDate(), r.creditAccountId(), reason);
        log.warn("Cuenta {} del {} sin soltar: {}", r.creditAccountId(), r.businessDate(), reason);
        return false;
    }
}
