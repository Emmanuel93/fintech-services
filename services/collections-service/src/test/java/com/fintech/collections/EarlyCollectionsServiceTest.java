package com.fintech.collections;

import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.service.EarlyCollectionsService;
import com.fintech.collections.domain.AccountBalanceSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class EarlyCollectionsServiceTest {

    @Mock AccountBalanceSnapshotRepository snapshotRepository;
    @Mock CollectionsEventPublisher eventPublisher;

    EarlyCollectionsService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EarlyCollectionsService(snapshotRepository, eventPublisher);
    }

    @Test
    void onInstallmentUpcoming_publishesReminder_whenAccountKnown() {
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.of(
                AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN")));

        LocalDate dueDate = LocalDate.now().plusDays(3);
        service.onInstallmentUpcoming(creditAccountId, dueDate, new BigDecimal("500"));

        then(eventPublisher).should().publishPreDueReminderTriggered(creditAccountId, obligorPartyId,
                dueDate, new BigDecimal("500"));
    }

    @Test
    void onInstallmentUpcoming_skips_whenAccountUnknown() {
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.empty());

        service.onInstallmentUpcoming(creditAccountId, LocalDate.now().plusDays(3), new BigDecimal("500"));

        then(eventPublisher).shouldHaveNoInteractions();
    }
}
