package com.fintech.configuration;

import com.fintech.configuration.application.CreateConfigParameterCommand;
import com.fintech.configuration.application.port.out.ConfigAuditRepository;
import com.fintech.configuration.application.port.out.ConfigEventPublisher;
import com.fintech.configuration.application.port.out.ConfigParameterRepository;
import com.fintech.configuration.application.service.ConfigurationService;
import com.fintech.configuration.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigurationServiceTest {

    @Mock ConfigParameterRepository parameterRepository;
    @Mock ConfigAuditRepository auditRepository;
    @Mock ConfigEventPublisher eventPublisher;

    ConfigurationService service;

    private final UUID makerUuid = UUID.randomUUID();
    private final UUID checkerUuid = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConfigurationService(parameterRepository, auditRepository, eventPublisher);
    }

    @Test
    void create_validCommand_returnsParamInPendingApproval() {
        given(parameterRepository.findMaxVersionByParamKey("vat_rate")).willReturn(0);
        given(parameterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateConfigParameterCommand("vat_rate", "0.16", null, null,
                LocalDate.now(), makerUuid);
        ConfigParameter param = service.create(cmd);

        assertThat(param.getStatus()).isEqualTo(ConfigParameterStatus.PENDING_APPROVAL);
        assertThat(param.getParamKey()).isEqualTo("vat_rate");
        assertThat(param.getVersion()).isEqualTo(1);
        then(auditRepository).should().save(any(ConfigAuditTrail.class));
    }

    @Test
    void create_secondVersion_incrementsVersion() {
        given(parameterRepository.findMaxVersionByParamKey("vat_rate")).willReturn(1);
        given(parameterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        var cmd = new CreateConfigParameterCommand("vat_rate", "0.18", null, null,
                LocalDate.now(), makerUuid);
        ConfigParameter param = service.create(cmd);

        assertThat(param.getVersion()).isEqualTo(2);
    }

    @Test
    void approve_pendingApproval_transitionsToActiveAndPublishesEvent() {
        UUID paramId = UUID.randomUUID();
        ConfigParameter pending = ConfigParameter.create("grace_period_days", "3",
                null, null, LocalDate.now(), makerUuid, null, 1);
        given(parameterRepository.findById(paramId)).willReturn(Optional.of(pending));
        given(parameterRepository.findByParamKeyAndStatus("grace_period_days", ConfigParameterStatus.ACTIVE))
                .willReturn(Optional.empty());
        given(parameterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        ConfigParameter approved = service.approve(paramId, checkerUuid);

        assertThat(approved.getStatus()).isEqualTo(ConfigParameterStatus.ACTIVE);
        assertThat(approved.getApprovedBy()).isEqualTo(checkerUuid);
        then(eventPublisher).should().publishConfigurationUpdated(any());
    }

    @Test
    void approve_deprecatesPreviousActiveVersion() {
        UUID newParamId = UUID.randomUUID();
        ConfigParameter pending = ConfigParameter.create("vat_rate", "0.18",
                null, null, LocalDate.now(), makerUuid, null, 2);
        ConfigParameter existingActive = ConfigParameter.create("vat_rate", "0.16",
                null, null, LocalDate.now(), makerUuid, null, 1);
        existingActive.approve(checkerUuid); // set to ACTIVE

        given(parameterRepository.findById(newParamId)).willReturn(Optional.of(pending));
        given(parameterRepository.findByParamKeyAndStatus("vat_rate", ConfigParameterStatus.ACTIVE))
                .willReturn(Optional.of(existingActive));
        given(parameterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.approve(newParamId, checkerUuid);

        assertThat(existingActive.getStatus()).isEqualTo(ConfigParameterStatus.DEPRECATED);
        then(parameterRepository).should(times(2)).save(any(ConfigParameter.class));
    }

    @Test
    void approve_paramNotFound_throwsNotFoundException() {
        UUID unknownId = UUID.randomUUID();
        given(parameterRepository.findById(unknownId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(unknownId, checkerUuid))
                .isInstanceOf(ConfigParameterNotFoundException.class);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void approve_alreadyActive_throwsInvalidTransition() {
        UUID paramId = UUID.randomUUID();
        ConfigParameter active = ConfigParameter.create("vat_rate", "0.16",
                null, null, LocalDate.now(), makerUuid, null, 1);
        active.approve(checkerUuid); // already ACTIVE

        given(parameterRepository.findById(paramId)).willReturn(Optional.of(active));
        given(parameterRepository.findByParamKeyAndStatus("vat_rate", ConfigParameterStatus.ACTIVE))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(paramId, checkerUuid))
                .isInstanceOf(InvalidConfigStateTransitionException.class);
    }

    @Test
    void getActive_returnsEmptyWhenNoActiveParam() {
        given(parameterRepository.findByParamKeyAndStatus("unknown_key", ConfigParameterStatus.ACTIVE))
                .willReturn(Optional.empty());

        var result = service.getActive("unknown_key", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void getHistory_returnsAllVersionsOrderedDesc() {
        ConfigParameter v1 = ConfigParameter.create("vat_rate", "0.16", null, null, null, makerUuid, null, 1);
        ConfigParameter v2 = ConfigParameter.create("vat_rate", "0.18", null, null, null, makerUuid, null, 2);
        given(parameterRepository.findAllByParamKeyOrderByVersionDesc("vat_rate"))
                .willReturn(List.of(v2, v1));

        var history = service.getHistory("vat_rate");

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getVersion()).isEqualTo(2);
    }
}
