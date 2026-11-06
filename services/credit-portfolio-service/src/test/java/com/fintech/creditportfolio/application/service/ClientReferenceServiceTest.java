package com.fintech.creditportfolio.application.service;

import com.fintech.creditportfolio.application.port.out.ClientReferenceRepository;
import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.domain.PaymentReference;
import com.fintech.creditportfolio.domain.beneficiary.ClientPaymentReference;
import com.fintech.creditportfolio.domain.event.ClientReferenceAssignedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ClientReferenceServiceTest {

    final List<ClientPaymentReference> store = new ArrayList<>();
    final ClientReferenceRepository repo = new ClientReferenceRepository() {
        @Override public ClientPaymentReference save(ClientPaymentReference r) { store.add(r); return r; }
        @Override public Optional<ClientPaymentReference> findByLineAndClient(UUID line, UUID client) {
            return store.stream().filter(r -> r.getCreditAccountId().equals(line) && r.getBeneficiaryPartyId().equals(client)).findFirst();
        }
        @Override public List<ClientPaymentReference> findByLine(UUID line) {
            return store.stream().filter(r -> r.getCreditAccountId().equals(line)).toList();
        }
        @Override public Optional<ClientPaymentReference> findByReference(long ref) {
            return store.stream().filter(r -> r.getPaymentReference() == ref).findFirst();
        }
        @Override public List<Pair> pairsWithoutReference() { return List.of(); }
    };
    final AtomicLong next = new AtomicLong(PaymentReference.MIN_BODY + 500);
    final CreditPortfolioEventPublisher publisher = mock(CreditPortfolioEventPublisher.class);
    final ClientReferenceService service = new ClientReferenceService(repo, next::getAndIncrement, publisher);

    @Test
    void oneReferencePerLineAndClient_validLuhn_publishedOnce() {
        UUID line = UUID.randomUUID(), her = UUID.randomUUID(), client = UUID.randomUUID();

        ClientPaymentReference first = service.ensure(line, her, client);
        ClientPaymentReference again = service.ensure(line, her, client);
        ClientPaymentReference onAnotherLine = service.ensure(UUID.randomUUID(), UUID.randomUUID(), client);

        assertThat(again.getPaymentReference()).isEqualTo(first.getPaymentReference());
        assertThat(onAnotherLine.getPaymentReference()).isNotEqualTo(first.getPaymentReference());
        assertThat(String.valueOf(first.getPaymentReference())).hasSize(7);
        assertThat(PaymentReference.isValid(first.getPaymentReference())).isTrue();
        assertThat(service.find(first.getPaymentReference())).contains(first);

        ArgumentCaptor<ClientReferenceAssignedEvent> events = ArgumentCaptor.forClass(ClientReferenceAssignedEvent.class);
        verify(publisher, times(2)).publishClientReferenceAssigned(events.capture());
        assertThat(events.getAllValues().getFirst()).isEqualTo(new ClientReferenceAssignedEvent(line, her, client, first.getPaymentReference()));
    }
}
