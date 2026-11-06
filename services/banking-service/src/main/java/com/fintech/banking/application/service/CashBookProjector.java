package com.fintech.banking.application.service;

import com.fintech.banking.application.port.out.CashMovementPublisher;
import com.fintech.banking.application.port.out.InternalMovementStore;
import com.fintech.banking.domain.InternalMovement;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * The treasury's cash book: what goes out through STP, the funding, the clients' payments and their returns, the
 * commissions paid. Each movement is saved once (by the fact that brought it) and announced, so accounting can
 * reconcile its bank account (1101) against the book — two records of the same money kept from different facts.
 *
 * <p>It is announced every time its fact is seen, not only the first: if the announcement failed after the movement
 * was saved, the redelivery of the fact announces it again. Accounting keeps it once, by its id.
 */
@Service
@Primary
public class CashBookProjector implements InternalMovementProjector {

    private final InternalMovementStore store;
    private final CashMovementPublisher publisher;

    public CashBookProjector(InternalMovementStore store, CashMovementPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Override
    public void project(InternalMovement movement) {
        publisher.recorded(store.save(movement));
    }

    @Override
    public boolean brought(String sourceEventId) {
        return store.contains(sourceEventId);
    }
}
