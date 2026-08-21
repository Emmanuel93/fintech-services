package com.fintech.origination;

import com.fintech.origination.application.RegisterProspectCommand;
import com.fintech.origination.application.RegisterProspectResult;
import com.fintech.origination.application.port.out.ProspectEventPublisher;
import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.application.service.ProspectService;
import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.DuplicateProspectException;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.PrivacyNoticeRequiredException;
import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.IncomeProofType;
import com.fintech.origination.domain.ProspectDocument;
import com.fintech.origination.domain.ProspectDocumentType;
import com.fintech.origination.domain.ProspectStatus;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.domain.event.ProspectCreatedEvent;
import com.fintech.origination.infrastructure.config.OriginationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ProspectServiceTest {

    @Mock ProspectRepository prospectRepository;
    @Mock ProspectEventPublisher eventPublisher;

    ProspectService prospectService;
    OriginationProperties properties;

    final List<ProspectDocument> sampleDocuments = List.of(
            new ProspectDocument(ProspectDocumentType.INE_FRONT,    "s3://bucket/docs/ine-front.jpg"),
            new ProspectDocument(ProspectDocumentType.INE_BACK,     "s3://bucket/docs/ine-back.jpg"),
            new ProspectDocument(ProspectDocumentType.ADDRESS_PROOF,"s3://bucket/docs/address-proof.pdf"),
            new ProspectDocument(ProspectDocumentType.INCOME_PROOF, "s3://bucket/docs/income-proof.pdf", IncomeProofType.PAYROLL)
    );

    /** Factory: builds a valid command. ProspectCreated no longer carries a product (ADR-001). */
    private RegisterProspectCommand command(ProspectType type, String curp, String phone,
                                            ChannelType channel, boolean privacy, boolean circulo,
                                            List<ProspectDocument> docs, String correlationId) {
        return new RegisterProspectCommand(
                type,
                "Carlos", "Ramírez", "Torres",
                curp, null,
                LocalDate.of(1985, 3, 20),
                Gender.MALE, "Ciudad de México",
                phone, "carlos@example.com",
                "Av. Insurgentes Sur", "1602", null,
                "Crédito Constructor", null, "CDMX", "CDMX", "03940", "MX",
                channel, privacy, circulo, docs,
                "carlosrt", "Password1!", correlationId);
    }

    final RegisterProspectCommand validCommand = command(
            ProspectType.INDIVIDUAL, "RATC850320HDFMRL09", "+5215512345678",
            ChannelType.MOBILE_APP, true, true, sampleDocuments, "correlation-001");

    @BeforeEach
    void setUp() {
        properties = new OriginationProperties();
        properties.setProspectExpiryDays(30);


        prospectService = new ProspectService(prospectRepository, eventPublisher, properties);
    }

    // ── Register ──────────────────────────────────────────────────────────

    @Test
    void register_validCommand_returnsProspectRegistered() {
        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);
        given(prospectRepository.save(any(Prospect.class))).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publish(any(ProspectCreatedEvent.class));

        RegisterProspectResult result = prospectService.register(validCommand);

        assertThat(result).isInstanceOf(RegisterProspectResult.ProspectRegistered.class);
        RegisterProspectResult.ProspectRegistered registered = (RegisterProspectResult.ProspectRegistered) result;
        assertThat(registered.curp()).isEqualTo("RATC850320HDFMRL09");
        assertThat(registered.phone()).isEqualTo("+5215512345678");
        assertThat(registered.prospectId()).isNotNull();
        assertThat(registered.expiresAt()).isAfter(registered.createdAt());
    }

    @Test
    void register_savesProspectWithCapturedStatus() {
        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);
        ArgumentCaptor<Prospect> captor = ArgumentCaptor.forClass(Prospect.class);
        given(prospectRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publish(any());

        prospectService.register(validCommand);

        Prospect saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(ProspectStatus.CAPTURED);
        assertThat(saved.isPrivacyNoticeAccepted()).isTrue();
        assertThat(saved.isCirculoConsentAccepted()).isTrue();
        assertThat(saved.getCurp()).isEqualTo("RATC850320HDFMRL09");
        assertThat(saved.getChannelType()).isEqualTo(ChannelType.MOBILE_APP);
    }

    @Test
    void register_publishesProspectCreatedEvent() {
        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);
        given(prospectRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ProspectCreatedEvent> eventCaptor = ArgumentCaptor.forClass(ProspectCreatedEvent.class);
        willDoNothing().given(eventPublisher).publish(eventCaptor.capture());

        prospectService.register(validCommand);

        ProspectCreatedEvent event = eventCaptor.getValue();
        assertThat(event.getCurp()).isEqualTo("RATC850320HDFMRL09");
        assertThat(event.getPhone()).isEqualTo("+5215512345678");
        assertThat(event.getChannelType()).isEqualTo(ChannelType.MOBILE_APP);
        assertThat(event.getEventId()).isNotNull();
        assertThat(event.getCorrelationId()).isEqualTo("correlation-001");
    }

    @Test
    void register_duplicateCurp_throwsDuplicateProspectException() {
        given(prospectRepository.existsByCurp("RATC850320HDFMRL09")).willReturn(true);

        assertThatThrownBy(() -> prospectService.register(validCommand))
                .isInstanceOf(DuplicateProspectException.class);

        then(prospectRepository).should(never()).save(any());
        then(eventPublisher).should(never()).publish(any());
    }

    @Test
    void register_duplicatePhone_throwsDuplicateProspectException() {
        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone("+5215512345678")).willReturn(true);

        assertThatThrownBy(() -> prospectService.register(validCommand))
                .isInstanceOf(DuplicateProspectException.class);

        then(prospectRepository).should(never()).save(any());
    }

    @Test
    void register_privacyNoticeNotAccepted_throwsPrivacyNoticeRequiredException() {
        RegisterProspectCommand noPrivacy = command(
                ProspectType.INDIVIDUAL, "RATC850320HDFMRL09", "+5215512345678",
                ChannelType.MOBILE_APP, false, true, sampleDocuments, null);

        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);

        assertThatThrownBy(() -> prospectService.register(noPrivacy))
                .isInstanceOf(PrivacyNoticeRequiredException.class);

        then(prospectRepository).should(never()).save(any());
    }

    @Test
    void register_circuloConsentNotAccepted_succeeds_withConsentFalseInEvent() {
        RegisterProspectCommand noCirculoConsent = command(
                ProspectType.INDIVIDUAL, "RATC850320HDFMRL09", "+5215512345678",
                ChannelType.MOBILE_APP, true, false, sampleDocuments, null);

        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);
        given(prospectRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RegisterProspectResult result = prospectService.register(noCirculoConsent);

        ArgumentCaptor<ProspectCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(ProspectCreatedEvent.class);
        then(eventPublisher).should().publish(eventCaptor.capture());
        assertThat(eventCaptor.getValue().isCirculoConsentAccepted()).isFalse();
        assertThat(result).isNotNull();
    }

    @Test
    void register_curpNormalisedToUpperCase() {
        RegisterProspectCommand lowerCurp = command(
                ProspectType.INDIVIDUAL, "ratc850320hdfmrl09", "+5215512345679",
                ChannelType.WEB, true, true, List.of(), null);

        given(prospectRepository.existsByCurp("RATC850320HDFMRL09")).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);
        ArgumentCaptor<Prospect> captor = ArgumentCaptor.forClass(Prospect.class);
        given(prospectRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publish(any());

        prospectService.register(lowerCurp);

        assertThat(captor.getValue().getCurp()).isEqualTo("RATC850320HDFMRL09");
    }

    @Test
    void register_defaultsToIndividualWhenProspectTypeNull() {
        RegisterProspectCommand noType = command(
                null, "LOAA900101MDFPNA00", "+5213312345678",
                ChannelType.WEB, true, true, List.of(), null);

        given(prospectRepository.existsByCurp(anyString())).willReturn(false);
        given(prospectRepository.existsByPhone(anyString())).willReturn(false);
        ArgumentCaptor<Prospect> captor = ArgumentCaptor.forClass(Prospect.class);
        given(prospectRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(eventPublisher).publish(any());

        prospectService.register(noType);

        assertThat(captor.getValue().getProspectType()).isEqualTo(ProspectType.INDIVIDUAL);
    }
}
