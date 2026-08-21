package com.fintech.creditportfolio.infrastructure.job;

import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.DispositionRepository;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.Disposition;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.DelinquencyStatusUpdatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Recalcula los días de atraso de una cuenta.
 *
 * <p>El criterio es el mismo para todos —días desde la cuota vencida más antigua que sigue sin
 * pagarse—. Lo único que cambia es <b>dónde viven esas cuotas</b>:
 *
 * <ul>
 *   <li>Un crédito amortizable tiene un calendario, y cuelga de la cuenta.</li>
 *   <li>Una revolvente tiene <b>uno por disposición</b>: la línea no se amortiza, se amortiza cada
 *       colocación. La mora se mide contra el conjunto — la distribuidora cae en mora cuando deja
 *       de cubrir el pago de lo que dispuso, venga de la colocación que venga.</li>
 * </ul>
 *
 * <p>Esto sólo miraba el calendario de la cuenta. Como una revolvente nunca escribe uno ahí, salía
 * invariablemente con cero días de atraso, y ese cero arrastraba a risk (STAGE_1 sin estimación), a
 * cobranza (ningún caso) y al quebranto (inalcanzable). No era que la mora fuera difícil de
 * producir: el cero era una constante, no una medición.
 */
@Component
public class DelinquencyAccountProcessor {

    private final CreditAccountRepository accountRepository;
    private final InstallmentRepository installmentRepository;
    private final DispositionRepository dispositionRepository;
    private final CreditPortfolioEventPublisher eventPublisher;

    public DelinquencyAccountProcessor(CreditAccountRepository accountRepository,
                                 InstallmentRepository installmentRepository,
                                 DispositionRepository dispositionRepository,
                                 CreditPortfolioEventPublisher eventPublisher) {
        this.accountRepository     = accountRepository;
        this.installmentRepository = installmentRepository;
        this.dispositionRepository = dispositionRepository;
        this.eventPublisher        = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void process(CreditAccount account, LocalDate today) {
        List<Installment> overdue = account.isRevolving()
                ? vencidasDeSusDisposiciones(account, today)
                : installmentRepository.findPendingOverdueByScheduleId(
                        account.getCreditAccountId(), today);

        int days = diasDesdeLaMasAntigua(overdue, today);

        account.updateDelinquency(days);
        accountRepository.save(account);

        eventPublisher.publishDelinquencyStatusUpdated(new DelinquencyStatusUpdatedEvent(
                account.getCreditAccountId(),
                account.getObligorPartyId(),
                account.getContractNumber(),
                days));
    }

    private List<Installment> vencidasDeSusDisposiciones(CreditAccount account, LocalDate today) {
        List<UUID> calendarios = dispositionRepository
                .findByCreditAccountId(account.getCreditAccountId()).stream()
                .map(Disposition::getDispositionId)
                .toList();
        return installmentRepository.findPendingOverdueByScheduleIds(calendarios, today);
    }

    private int diasDesdeLaMasAntigua(List<Installment> overdue, LocalDate today) {
        if (overdue.isEmpty()) return 0;
        LocalDate earliest = overdue.stream()
                .map(Installment::getDueDate)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        return (int) ChronoUnit.DAYS.between(earliest, today);
    }
}
