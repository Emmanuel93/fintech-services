package com.fintech.collections;

import com.fintech.collections.application.ApproveWriteOffCommand;
import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.RequestWriteOffCommand;
import com.fintech.collections.application.port.out.AccountBalanceSnapshotRepository;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.WriteOffRecordRepository;
import com.fintech.collections.application.service.BureauReportingService;
import com.fintech.collections.application.service.WriteOffService;
import com.fintech.collections.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class WriteOffServiceTest {

    @Mock CollectionCaseRepository caseRepository;
    @Mock WriteOffRecordRepository writeOffRepository;
    @Mock AccountBalanceSnapshotRepository snapshotRepository;
    @Mock BureauReportingService bureauReportingService;
    @Mock CollectionsEventPublisher eventPublisher;

    WriteOffService service;
    CollectionsProperties properties;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new CollectionsProperties(); // writeOffThresholdDays defaults to 181
        service = new WriteOffService(caseRepository, writeOffRepository, snapshotRepository,
                bureauReportingService, eventPublisher, properties);
    }

    private CollectionCase eligibleCase(int daysDelinquent) {
        return CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                daysDelinquent, new BigDecimal("5000"), "WRITEOFF_CANDIDATE");
    }

    @Test
    void request_publishesWriteOffRequested_withoutPersisting() {
        CollectionCase c = eligibleCase(200);
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(writeOffRepository.existsByCreditAccountId(creditAccountId)).willReturn(false);
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.empty());

        var cmd = new RequestWriteOffCommand(c.getCaseId(), WriteOffReason.UNRECOVERABLE, "agent-1");
        service.request(cmd);

        then(eventPublisher).should().publishWriteOffRequested(c.getCaseId(), creditAccountId,
                new BigDecimal("5000"), 200, "UNRECOVERABLE", "agent-1");
        then(writeOffRepository).should(never()).save(any());
    }

    @Test
    void request_throws_whenAccountAlreadyWrittenOff() {
        CollectionCase c = eligibleCase(200);
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(writeOffRepository.existsByCreditAccountId(creditAccountId)).willReturn(true);

        var cmd = new RequestWriteOffCommand(c.getCaseId(), WriteOffReason.UNRECOVERABLE, "agent-1");

        assertThatThrownBy(() -> service.request(cmd)).isInstanceOf(InvalidCaseStateException.class);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void request_throws_whenBelowWriteOffThreshold() {
        CollectionCase c = eligibleCase(90);
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(writeOffRepository.existsByCreditAccountId(creditAccountId)).willReturn(false);

        var cmd = new RequestWriteOffCommand(c.getCaseId(), WriteOffReason.UNRECOVERABLE, "agent-1");

        assertThatThrownBy(() -> service.request(cmd)).isInstanceOf(InvalidCaseStateException.class);
    }

    @Test
    void approve_createsImmutableRecord_marksCase_andReportsToBureau() {
        CollectionCase c = eligibleCase(200);
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(writeOffRepository.existsByCreditAccountId(creditAccountId)).willReturn(false);
        AccountBalanceSnapshot snapshot = AccountBalanceSnapshot.init(creditAccountId, obligorPartyId, "PERSONAL_LOAN");
        snapshot.upsert(new BigDecimal("4000"), new BigDecimal("800"), new BigDecimal("200"),
                new BigDecimal("5000"), 1L);
        given(snapshotRepository.findById(creditAccountId)).willReturn(Optional.of(snapshot));
        given(writeOffRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(caseRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new ApproveWriteOffCommand(c.getCaseId(), WriteOffReason.UNRECOVERABLE, "ops-1", "AUTH-1");
        WriteOffRecord record = service.approve(cmd);

        assertThat(record.getPrincipalWrittenOff()).isEqualByComparingTo("4000");
        assertThat(record.getInterestWrittenOff()).isEqualByComparingTo("800");
        assertThat(record.getPenaltyWrittenOff()).isEqualByComparingTo("200");
        assertThat(record.getTotalWrittenOff()).isEqualByComparingTo("5000");
        assertThat(c.getStatus()).isEqualTo(CaseStatus.WRITTEN_OFF);
        then(eventPublisher).should().publishWriteOffExecuted(record);
        then(bureauReportingService).should().createPendingReport(creditAccountId, obligorPartyId,
                BureauEventType.WRITE_OFF, record.getWriteOffId(), record.getTotalWrittenOff());
    }

    @Test
    void approve_throws_whenAccountAlreadyWrittenOff() {
        CollectionCase c = eligibleCase(200);
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(writeOffRepository.existsByCreditAccountId(creditAccountId)).willReturn(true);

        var cmd = new ApproveWriteOffCommand(c.getCaseId(), WriteOffReason.UNRECOVERABLE, "ops-1", "AUTH-1");

        assertThatThrownBy(() -> service.approve(cmd)).isInstanceOf(InvalidCaseStateException.class);
        then(writeOffRepository).should(never()).save(any());
    }
}
