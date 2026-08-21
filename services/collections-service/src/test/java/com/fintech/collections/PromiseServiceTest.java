package com.fintech.collections;

import com.fintech.collections.application.CreatePaymentPromiseCommand;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.PaymentPromiseRepository;
import com.fintech.collections.application.port.out.WriteOffRecordRepository;
import com.fintech.collections.application.service.CommunicationHoldService;
import com.fintech.collections.application.service.DunningService;
import com.fintech.collections.application.service.PromiseService;
import com.fintech.collections.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class PromiseServiceTest {

    @Mock CollectionCaseRepository caseRepository;
    @Mock PaymentPromiseRepository promiseRepository;
    @Mock WriteOffRecordRepository writeOffRepository;
    @Mock CollectionsEventPublisher eventPublisher;
    @Mock CommunicationHoldService holdService;
    @Mock DunningService dunningService;

    PromiseService service;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PromiseService(caseRepository, promiseRepository, writeOffRepository, eventPublisher,
                holdService, dunningService);
    }

    private CollectionCase openCase() {
        return CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                45, new BigDecimal("2000"), "AGENT_ASSIGNED_RESTRUCTURE_OFFER");
    }

    @Test
    void create_success_whenCaseActiveAndNoExistingPromise() {
        CollectionCase c = openCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(promiseRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.empty());
        given(promiseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new CreatePaymentPromiseCommand(c.getCaseId(), new BigDecimal("500"),
                LocalDate.now().plusDays(5), "agent-1");

        PaymentPromise result = service.create(cmd);

        assertThat(result.isActive()).isTrue();
        then(eventPublisher).should().publishPaymentPromiseMade(result);
    }

    @Test
    void create_throws_whenCaseTerminal() {
        CollectionCase c = openCase();
        c.markWrittenOff();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));

        var cmd = new CreatePaymentPromiseCommand(c.getCaseId(), new BigDecimal("500"),
                LocalDate.now().plusDays(5), "agent-1");

        assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(InvalidCaseStateException.class);
        then(promiseRepository).should(never()).save(any());
    }

    @Test
    void create_throws_whenActivePromiseAlreadyExists() {
        CollectionCase c = openCase();
        PaymentPromise existing = PaymentPromise.create(c.getCaseId(), new BigDecimal("300"),
                LocalDate.now().plusDays(2), "agent-1");
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(promiseRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.of(existing));

        var cmd = new CreatePaymentPromiseCommand(c.getCaseId(), new BigDecimal("500"),
                LocalDate.now().plusDays(5), "agent-1");

        assertThatThrownBy(() -> service.create(cmd)).isInstanceOf(InvalidCaseStateException.class);
    }

    @Test
    void onPaymentApplied_marksPromiseKept_whenAmountCoversIt() {
        CollectionCase c = openCase();
        PaymentPromise promise = PaymentPromise.create(c.getCaseId(), new BigDecimal("300"),
                LocalDate.now().plusDays(2), "agent-1");
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.of(c));
        given(promiseRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.of(promise));

        UUID paymentId = UUID.randomUUID();
        service.onPaymentApplied(creditAccountId, paymentId, new BigDecimal("300"), "SPEI");

        assertThat(promise.getStatus()).isEqualTo(PromiseStatus.KEPT);
        then(promiseRepository).should().save(promise);
    }

    @Test
    void onPaymentApplied_doesNotAffectPromise_whenAmountIsPartial() {
        CollectionCase c = openCase();
        PaymentPromise promise = PaymentPromise.create(c.getCaseId(), new BigDecimal("300"),
                LocalDate.now().plusDays(2), "agent-1");
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.of(c));
        given(promiseRepository.findActiveByCaseId(c.getCaseId())).willReturn(Optional.of(promise));

        service.onPaymentApplied(creditAccountId, UUID.randomUUID(), new BigDecimal("100"), "SPEI");

        assertThat(promise.isActive()).isTrue();
        then(promiseRepository).should(never()).save(any());
    }

    @Test
    void onPaymentApplied_publishesRecovery_whenNoActiveCaseButAccountWasWrittenOff() {
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.empty());
        WriteOffRecord writeOff = WriteOffRecord.create(UUID.randomUUID(), creditAccountId, obligorPartyId,
                new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, "ops", "REF-1", WriteOffReason.UNRECOVERABLE);
        given(writeOffRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.of(writeOff));

        service.onPaymentApplied(creditAccountId, UUID.randomUUID(), new BigDecimal("200"), "SPEI");

        then(eventPublisher).should().publishRecoveryPaymentApplied(writeOff.getWriteOffId(), creditAccountId,
                new BigDecimal("200"), "SPEI");
    }

    @Test
    void onPaymentApplied_noOp_whenNoActiveCaseAndNoWriteOff() {
        given(caseRepository.findActiveByCreditAccountId(creditAccountId)).willReturn(Optional.empty());
        given(writeOffRepository.findByCreditAccountId(creditAccountId)).willReturn(Optional.empty());

        service.onPaymentApplied(creditAccountId, UUID.randomUUID(), new BigDecimal("200"), "SPEI");

        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void checkBrokenPromises_marksOverdueActivePromisesBroken() {
        PaymentPromise promise = PaymentPromise.create(UUID.randomUUID(), new BigDecimal("300"),
                LocalDate.now().plusDays(1), "agent-1");
        given(promiseRepository.findActiveWithPromisedDateBefore(any())).willReturn(List.of(promise));

        service.checkBrokenPromises(LocalDate.now());

        assertThat(promise.getStatus()).isEqualTo(PromiseStatus.BROKEN);
        then(eventPublisher).should().publishPaymentPromiseBroken(promise);
    }
}
