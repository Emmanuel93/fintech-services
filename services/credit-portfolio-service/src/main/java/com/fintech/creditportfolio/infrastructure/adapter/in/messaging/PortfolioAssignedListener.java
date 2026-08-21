package com.fintech.creditportfolio.infrastructure.adapter.in.messaging;

import com.fintech.creditportfolio.application.port.in.FindCreditAccountUseCase;
import com.fintech.creditportfolio.application.port.out.CreditAccountRepository;
import com.fintech.creditportfolio.domain.CreditAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sella la sucursal de origen del crédito.
 *
 * <p><b>Se sella una vez y no cambia nunca.</b> Es el eje de la contabilidad: si la sucursal se
 * recalculara cada vez que la cartera se reasigna, la balanza de marzo daría otro número en agosto y
 * los estados financieros dejarían de ser reproducibles. El ejecutivo, que sí rota, vive en party.
 *
 * <p>{@code sealOriginUnit} no sobrescribe, así que reprocesar el evento es inocuo y una
 * reasignación posterior no puede mover la atribución contable ni por accidente.
 */
@Component
public class PortfolioAssignedListener {

    private static final Logger log = LoggerFactory.getLogger(PortfolioAssignedListener.class);

    private final FindCreditAccountUseCase findUseCase;
    private final CreditAccountRepository accountRepository;

    public PortfolioAssignedListener(FindCreditAccountUseCase findUseCase,
                                      CreditAccountRepository accountRepository) {
        this.findUseCase       = findUseCase;
        this.accountRepository = accountRepository;
    }

    @KafkaListener(topics = "sales-org.portfolio-assigned",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "portfolioAssignedListenerContainerFactory")
    @Transactional
    public void onMessage(PortfolioAssignedPayload p) {
        if (p.creditAccountId() == null || p.unitCode() == null || p.unitCode().isBlank()) return;
        try {
            CreditAccount account = findUseCase.getById(p.creditAccountId());
            account.sealOriginUnit(p.unitCode());
            accountRepository.save(account);
            log.info("Sucursal sellada creditAccountId={} unidad={}", p.creditAccountId(), p.unitCode());
        } catch (Exception e) {
            log.warn("No se pudo sellar la sucursal de {}: {}", p.creditAccountId(), e.getMessage());
        }
    }
}
