package com.fintech.creditportfolio;

import com.fintech.creditportfolio.application.port.out.CreditPortfolioEventPublisher;
import com.fintech.creditportfolio.application.port.out.InstallmentRepository;
import com.fintech.creditportfolio.domain.Installment;
import com.fintech.creditportfolio.domain.event.InstallmentDueEvent;
import com.fintech.creditportfolio.infrastructure.job.InstallmentDueJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * Unit tests for InstallmentDueJob.
 *
 * ID-01  No installments due today → no events published
 * ID-02  Single installment due → one InstallmentDueEvent published with correct fields
 * ID-03  Multiple installments due → one event per installment
 * ID-04  Publisher throws for one installment → job continues with remaining installments
 */
@ExtendWith(MockitoExtension.class)
class InstallmentDueJobTest {

    @Mock InstallmentRepository installmentRepository;
    @Mock CreditPortfolioEventPublisher eventPublisher;

    InstallmentDueJob job;

    @BeforeEach
    void setUp() {
        job = new InstallmentDueJob(installmentRepository, eventPublisher);
    }

    // ── ID-01 ──────────────────────────────────────────────────────────────────

    @Test
    void id01_noInstallmentsDue_noEventsPublished() {
        given(installmentRepository.findDueOnOrBefore(any())).willReturn(List.of());

        job.run();

        verify(eventPublisher, never()).publishInstallmentDue(any());
    }

    // ── ID-02 ──────────────────────────────────────────────────────────────────

    @Test
    void id02_singleInstallmentDue_publishesCorrectEvent() {
        LocalDate today = LocalDate.now();
        UUID creditAccountId = UUID.randomUUID();
        Installment inst = Installment.of(creditAccountId, 3, today,
                new BigDecimal("4200.00"), new BigDecimal("90.00"));

        given(installmentRepository.findDueOnOrBefore(today)).willReturn(List.of(inst));

        job.run();

        ArgumentCaptor<InstallmentDueEvent> captor = ArgumentCaptor.forClass(InstallmentDueEvent.class);
        verify(eventPublisher).publishInstallmentDue(captor.capture());

        InstallmentDueEvent event = captor.getValue();
        assertThat(event.getInstallmentId()).isEqualTo(inst.getInstallmentId());
        assertThat(event.getCreditAccountId()).isEqualTo(creditAccountId);
        assertThat(event.getInstallmentNumber()).isEqualTo(3);
        assertThat(event.getDueDate()).isEqualTo(today);
        assertThat(event.getTotalAmount()).isEqualByComparingTo("4290.00");
    }

    // ── ID-03 ──────────────────────────────────────────────────────────────────

    @Test
    void id03_multipleInstallmentsDue_publishesOneEventEach() {
        LocalDate today = LocalDate.now();
        Installment inst1 = Installment.of(UUID.randomUUID(), 1, today,
                new BigDecimal("5000.00"), new BigDecimal("150.00"));
        Installment inst2 = Installment.of(UUID.randomUUID(), 2, today,
                new BigDecimal("3000.00"), new BigDecimal("80.00"));

        given(installmentRepository.findDueOnOrBefore(today)).willReturn(List.of(inst1, inst2));

        job.run();

        verify(eventPublisher, times(2)).publishInstallmentDue(any());
    }

    // ── ID-04 ──────────────────────────────────────────────────────────────────

    @Test
    void id04_publisherThrowsForOneInstallment_continuesWithRemaining() {
        LocalDate today = LocalDate.now();
        Installment inst1 = Installment.of(UUID.randomUUID(), 1, today,
                new BigDecimal("5000.00"), new BigDecimal("100.00"));
        Installment inst2 = Installment.of(UUID.randomUUID(), 2, today,
                new BigDecimal("3000.00"), new BigDecimal("80.00"));

        given(installmentRepository.findDueOnOrBefore(today)).willReturn(List.of(inst1, inst2));
        doThrow(new RuntimeException("Kafka unavailable"))
                .doNothing()
                .when(eventPublisher).publishInstallmentDue(any());

        job.run();   // must not throw

        verify(eventPublisher, times(2)).publishInstallmentDue(any());
    }
}
