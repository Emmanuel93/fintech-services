package com.fintech.disbursement.application.service;

import com.fintech.disbursement.application.port.in.CancelDisbursementUseCase;
import com.fintech.disbursement.application.port.in.FindDisbursementUseCase;
import com.fintech.disbursement.application.port.out.DisbursementEventPublisher;
import com.fintech.disbursement.application.port.out.DisbursementEventRepository;
import com.fintech.disbursement.application.port.out.DisbursementOrderRepository;
import com.fintech.disbursement.domain.DisbursementEvent;
import com.fintech.disbursement.domain.DisbursementNotFoundException;
import com.fintech.disbursement.domain.DisbursementOrder;
import com.fintech.disbursement.domain.DisbursementStatus;
import com.fintech.disbursement.domain.FailureCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class DisbursementQueryService implements FindDisbursementUseCase, CancelDisbursementUseCase {

    private final DisbursementOrderRepository orders;
    private final DisbursementEventRepository events;
    private final DisbursementEventPublisher publisher;

    public DisbursementQueryService(DisbursementOrderRepository orders,
                                    DisbursementEventRepository events,
                                    DisbursementEventPublisher publisher) {
        this.orders = orders;
        this.events = events;
        this.publisher = publisher;
    }

    @Override
    @Transactional(readOnly = true)
    public DisbursementOrder findById(UUID disbursementId) {
        return orders.findById(disbursementId)
                .orElseThrow(() -> new DisbursementNotFoundException(
                        "No existe el desembolso " + disbursementId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisbursementEvent> timeline(UUID disbursementId) {
        findById(disbursementId);
        return events.findByDisbursementId(disbursementId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisbursementOrder> findByCompany(UUID companyId, int page, int size) {
        return orders.findByCompany(companyId, page, size);
    }

    /**
     * Sólo antes de despachar. Una vez que el proveedor tiene la orden, "cancelar" no la detiene:
     * sólo haría que nuestra base diga algo distinto de lo que pasa en el banco.
     */
    @Override
    @Transactional
    public DisbursementOrder cancel(UUID disbursementId, String reason, String actor) {
        DisbursementOrder order = findById(disbursementId);
        DisbursementStatus from = order.status();
        order.cancel(reason);
        orders.save(order);
        events.save(DisbursementEvent.record(order, from, DisbursementStatus.CANCELLED,
                FailureCode.CANCELLED_BY_OPERATOR.name(), reason, actor));
        publisher.publishFailed(order);
        return order;
    }
}
