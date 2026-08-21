package com.fintech.collections;

import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.service.CaseManagementService;
import com.fintech.collections.domain.AccountBalanceSnapshot;
import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.CollectionCaseNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CaseManagementServiceTest {

    @Mock CollectionCaseRepository caseRepository;
    @Mock AccountBalanceSnapshotRepository snapshotRepository;
    @Mock CollectionsEventPublisher eventPublisher;

    CaseManagementService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CaseManagementService(caseRepository, snapshotRepository, eventPublisher);
    }

    @Test
    void onDelinquencyStatusUpdated_opensNewCase_whenNoneExistsAndBucketNotCurrent() {
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.empty());
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.of(
                AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN")));
        given(caseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onDelinquencyStatusUpdated(creditAccountId, obligorPartyId, 15);

        ArgumentCaptor<CollectionCase> captor = ArgumentCaptor.forClass(CollectionCase.class);
        then(caseRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(captor.getValue().getProductType()).isEqualTo("PERSONAL_LOAN");
        then(eventPublisher).should().publishCollectionCaseCreated(any());
    }

    @Test
    void onDelinquencyStatusUpdated_doesNotOpenCase_whenBucketIsCurrent() {
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.empty());

        service.onDelinquencyStatusUpdated(creditAccountId, obligorPartyId, 0);

        then(caseRepository).should(never()).save(any());
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void onDelinquencyStatusUpdated_escalatesExistingCase_whenBucketChanges() {
        CollectionCase existing = CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                10, new BigDecimal("1000"), "AUTO_NOTIFY");
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.of(existing));
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.of(
                AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN")));
        given(caseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onDelinquencyStatusUpdated(creditAccountId, obligorPartyId, 45);

        assertThat(existing.getCurrentBucket().name()).isEqualTo("B31_60");
        then(eventPublisher).should().publishCollectionCaseEscalated(any(), eq("B1_30"));
    }

    @Test
    void onDelinquencyStatusUpdated_closesCase_whenDelinquencyClears() {
        CollectionCase existing = CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                10, new BigDecimal("1000"), "AUTO_NOTIFY");
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.of(existing));
        given(caseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onDelinquencyStatusUpdated(creditAccountId, obligorPartyId, 0);

        assertThat(existing.getStatus()).isEqualTo(CaseStatus.CLOSED);
    }

    @Test
    void onBalanceUpdated_createsSnapshot_whenNoneExists() {
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.empty());
        given(snapshotRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.onBalanceUpdated(creditAccountId, obligorPartyId, new BigDecimal("5000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5000"), 1L);

        ArgumentCaptor<AccountBalanceSnapshot> captor = ArgumentCaptor.forClass(AccountBalanceSnapshot.class);
        then(snapshotRepository).should().save(captor.capture());
        assertThat(captor.getValue().getTotalDebt()).isEqualByComparingTo("5000");
    }

    @Test
    void getById_notFound_throws() {
        UUID caseId = UUID.randomUUID();
        given(caseRepository.findById(caseId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(caseId))
                .isInstanceOf(CollectionCaseNotFoundException.class);
    }

    /**
     * Kafka listeners can deliver DelinquencyStatusUpdated for many distinct accounts concurrently.
     * Each account is independent (no shared mutable state across CollectionCase instances) —
     * verifies the service handles concurrent, unrelated deliveries without cross-contamination
     * or exceptions.
     */
    @Test
    void onDelinquencyStatusUpdated_concurrentDistinctAccounts_allSucceed() throws InterruptedException {
        int threads = 16;
        List<UUID> accountIds = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            UUID id = UUID.randomUUID();
            accountIds.add(id);
            given(caseRepository.findActiveByCreditAccountId(id)).willReturn(Optional.empty());
            given(snapshotRepository.findById(id)).willReturn(Optional.of(
                    AccountBalanceSnapshot.init(id, obligorPartyId, "PERSONAL_LOAN")));
        }
        given(caseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    gate.await();
                    service.onDelinquencyStatusUpdated(accountIds.get(idx), obligorPartyId, 15);
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpected).as("unexpected exceptions under concurrent load: %s", unexpected).isEmpty();
        assertThat(successes.get()).isEqualTo(threads);
    }
}
