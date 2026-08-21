package com.fintech.collections;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.RecordContactAttemptCommand;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.application.port.out.ContactAttemptRepository;
import com.fintech.collections.application.service.ContactService;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.ContactAttempt;
import com.fintech.collections.domain.ContactChannel;
import com.fintech.collections.domain.ContactResult;
import com.fintech.collections.domain.InvalidCaseStateException;
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
class ContactServiceTest {

    @Mock CollectionCaseRepository caseRepository;
    @Mock ContactAttemptRepository attemptRepository;
    @Mock CollectionsEventPublisher eventPublisher;

    ContactService service;
    CollectionsProperties properties;

    private final UUID creditAccountId = UUID.randomUUID();
    private final UUID obligorPartyId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new CollectionsProperties();
        // wide-open window (0-24) so happy-path tests never flake on the current wall-clock hour
        properties.setContactAllowedHoursStart(0);
        properties.setContactAllowedHoursEnd(24);
        properties.setMaxContactAttemptsPerDay(3);
        service = new ContactService(caseRepository, attemptRepository, eventPublisher, properties);
    }

    private CollectionCase openCase() {
        return CollectionCase.open(creditAccountId, obligorPartyId, "PERSONAL_LOAN",
                45, new BigDecimal("2000"), "AGENT_ASSIGNED_RESTRUCTURE_OFFER");
    }

    @Test
    void record_success_withinWindowAndUnderDailyLimit() {
        CollectionCase c = openCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(attemptRepository.countManualByCaseIdAndAttemptedAtAfter(any(), any())).willReturn(1L);
        given(attemptRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new RecordContactAttemptCommand(c.getCaseId(), ContactChannel.PHONE, ContactResult.NO_ANSWER, "agent-1");

        ContactAttempt result = service.record(cmd);

        assertThat(result.getResult()).isEqualTo(ContactResult.NO_ANSWER);
        then(eventPublisher).should().publishContactAttemptRegistered(result);
    }

    @Test
    void record_throws_whenCaseIsTerminal() {
        CollectionCase c = openCase();
        c.close();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));

        var cmd = new RecordContactAttemptCommand(c.getCaseId(), ContactChannel.PHONE, ContactResult.NO_ANSWER, "agent-1");

        assertThatThrownBy(() -> service.record(cmd)).isInstanceOf(InvalidCaseStateException.class);
        then(attemptRepository).should(never()).save(any());
    }

    @Test
    void record_throws_whenOutsideAllowedHours() {
        properties.setContactAllowedHoursStart(0);
        properties.setContactAllowedHoursEnd(0); // hour>=0 always true -> always rejected
        CollectionCase c = openCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));

        var cmd = new RecordContactAttemptCommand(c.getCaseId(), ContactChannel.PHONE, ContactResult.NO_ANSWER, "agent-1");

        assertThatThrownBy(() -> service.record(cmd)).isInstanceOf(InvalidCaseStateException.class);
        then(attemptRepository).should(never()).save(any());
    }

    @Test
    void record_throws_whenDailyLimitReached() {
        CollectionCase c = openCase();
        given(caseRepository.findById(c.getCaseId())).willReturn(Optional.of(c));
        given(attemptRepository.countManualByCaseIdAndAttemptedAtAfter(any(), any())).willReturn(3L);

        var cmd = new RecordContactAttemptCommand(c.getCaseId(), ContactChannel.PHONE, ContactResult.NO_ANSWER, "agent-1");

        assertThatThrownBy(() -> service.record(cmd)).isInstanceOf(InvalidCaseStateException.class);
        then(attemptRepository).should(never()).save(any());
    }
}
