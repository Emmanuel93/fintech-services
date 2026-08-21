package com.fintech.notifications;

import com.fintech.notifications.application.port.out.CreditAccountProgressRepository;
import com.fintech.notifications.application.port.out.NotificationPreferenceRepository;
import com.fintech.notifications.application.service.ContactInfo;
import com.fintech.notifications.application.service.ContactResolutionService;
import com.fintech.notifications.application.service.NotificationDispatchService;
import com.fintech.notifications.application.service.NotificationTriggerService;
import com.fintech.notifications.domain.CreditAccountProgress;
import com.fintech.notifications.domain.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class NotificationTriggerServiceTest {

    @Mock ContactResolutionService contactResolutionService;
    @Mock NotificationDispatchService dispatchService;
    @Mock NotificationPreferenceRepository preferenceRepository;
    @Mock CreditAccountProgressRepository progressRepository;

    NotificationTriggerService triggerService;

    final UUID creditAccountId = UUID.randomUUID();
    final UUID obligorPartyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        triggerService = new NotificationTriggerService(
                contactResolutionService, dispatchService, preferenceRepository, progressRepository);
    }

    @Test
    void onOfferPresented_dispatchesUsingProspectIdAsRecipient() {
        UUID applicationId = UUID.randomUUID();
        UUID prospectId = UUID.randomUUID();
        given(contactResolutionService.resolveProspectContact(prospectId))
                .willReturn(new ContactInfo("Ana", "5511112222", "ana@example.com"));

        triggerService.onOfferPresented("evt-1", applicationId, prospectId,
                new BigDecimal("10000"), 12, new BigDecimal("0.35"), new BigDecimal("0.40"), null);

        then(contactResolutionService).should().onOfferPresented(applicationId, prospectId, 12);
        then(dispatchService).should().dispatch(eq("evt-1"), eq(EventType.OFFER_PRESENTED), eq(prospectId),
                any(), eq(null), any());
    }

    @Test
    void onPaymentApplied_noProgress_skipsSilently_NT11() {
        given(progressRepository.findById(creditAccountId)).willReturn(Optional.empty());

        triggerService.onPaymentApplied("evt-2", creditAccountId, new BigDecimal("500.00"));

        then(dispatchService).should(never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onPaymentApplied_amountCoversCurrentInstallment_dispatchesInstallmentPaid() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN", 12);
        progress.updateCurrentInstallment(LocalDate.now().plusDays(2), new BigDecimal("500.00"));
        given(progressRepository.findById(creditAccountId)).willReturn(Optional.of(progress));
        given(contactResolutionService.resolvePartyContact(obligorPartyId)).willReturn(ContactInfo.empty());

        triggerService.onPaymentApplied("evt-3", creditAccountId, new BigDecimal("500.00"));

        then(dispatchService).should().dispatch(eq("evt-3"), eq(EventType.INSTALLMENT_PAID), eq(obligorPartyId),
                any(), any(), any());
        then(progressRepository).should().save(progress);
    }

    @Test
    void onPaymentApplied_amountDoesNotCoverInstallment_doesNotDispatch_NT11() {
        CreditAccountProgress progress = CreditAccountProgress.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN", 12);
        progress.updateCurrentInstallment(LocalDate.now().plusDays(2), new BigDecimal("500.00"));
        given(progressRepository.findById(creditAccountId)).willReturn(Optional.of(progress));

        triggerService.onPaymentApplied("evt-4", creditAccountId, new BigDecimal("100.00"));

        then(dispatchService).should(never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onBalanceUpdated_notSettled_doesNotDispatch() {
        triggerService.onBalanceUpdated("evt-5", creditAccountId, obligorPartyId, "ACTIVE");

        then(dispatchService).should(never()).dispatch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onBalanceUpdated_settled_dispatchesLoanSettled() {
        given(progressRepository.findById(creditAccountId)).willReturn(Optional.empty());
        given(contactResolutionService.resolvePartyContact(obligorPartyId)).willReturn(ContactInfo.empty());

        triggerService.onBalanceUpdated("evt-6", creditAccountId, obligorPartyId, "SETTLED");

        then(dispatchService).should().dispatch(eq("evt-6"), eq(EventType.LOAN_SETTLED), eq(obligorPartyId),
                any(), any(), any());
    }

    @Test
    void onPreDueReminderTriggered_updatesProgressAndDispatchesReminder() {
        given(progressRepository.findById(creditAccountId)).willReturn(Optional.empty());
        given(contactResolutionService.resolvePartyContact(obligorPartyId)).willReturn(ContactInfo.empty());
        LocalDate dueDate = LocalDate.now().plusDays(3);

        triggerService.onPreDueReminderTriggered("evt-7", creditAccountId, obligorPartyId,
                dueDate, new BigDecimal("500.00"));

        ArgumentCaptor<CreditAccountProgress> captor = ArgumentCaptor.forClass(CreditAccountProgress.class);
        then(progressRepository).should().save(captor.capture());
        assertThat(captor.getValue().getCurrentDueDate()).isEqualTo(dueDate);
        then(dispatchService).should().dispatch(eq("evt-7"), eq(EventType.PAYMENT_REMINDER), eq(obligorPartyId),
                any(), any(), any());
    }
}
