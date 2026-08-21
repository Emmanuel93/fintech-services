package com.fintech.collections.application.service;

import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.RecordContactAttemptCommand;
import com.fintech.collections.application.port.in.RecordContactAttemptUseCase;
import com.fintech.collections.application.port.out.CollectionCaseRepository;
import com.fintech.collections.application.port.out.ContactAttemptRepository;
import com.fintech.collections.application.port.out.CollectionsEventPublisher;
import com.fintech.collections.domain.CollectionCaseNotFoundException;
import com.fintech.collections.domain.ContactAttempt;
import com.fintech.collections.domain.InvalidCaseStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/** CT-01..CT-04: CONDUSEF-bound contact recording. */
@Service
@Transactional
public class ContactService implements RecordContactAttemptUseCase {

    private static final Logger log = LoggerFactory.getLogger(ContactService.class);

    private final CollectionCaseRepository caseRepository;
    private final ContactAttemptRepository attemptRepository;
    private final CollectionsEventPublisher eventPublisher;
    private final CollectionsProperties properties;

    public ContactService(CollectionCaseRepository caseRepository,
                           ContactAttemptRepository attemptRepository,
                           CollectionsEventPublisher eventPublisher,
                           CollectionsProperties properties) {
        this.caseRepository    = caseRepository;
        this.attemptRepository = attemptRepository;
        this.eventPublisher    = eventPublisher;
        this.properties        = properties;
    }

    @Override
    public ContactAttempt record(RecordContactAttemptCommand cmd) {
        var collectionCase = caseRepository.findById(cmd.caseId())
                .orElseThrow(() -> new CollectionCaseNotFoundException(cmd.caseId().toString()));
        if (collectionCase.getStatus().isTerminal()) {
            // CC-04: CLOSED/WRITTEN_OFF no acepta nuevos contactos
            throw new InvalidCaseStateException("Case " + cmd.caseId() + " is " + collectionCase.getStatus()
                    + " — cannot record new contact attempts");
        }

        // CT-02: allowed hours only (regulatory window)
        int hour = LocalTime.now(ZoneId.systemDefault()).getHour();
        if (hour < properties.getContactAllowedHoursStart() || hour >= properties.getContactAllowedHoursEnd()) {
            throw new InvalidCaseStateException("Contact attempts only allowed between "
                    + properties.getContactAllowedHoursStart() + ":00 and " + properties.getContactAllowedHoursEnd() + ":00");
        }

        // CT-03: tope diario. Cuenta sólo los intentos MANUAL: si los mensajes de la cadencia
        // consumieran el cupo, un agente llegaría a las 10:30 sin intentos disponibles por tres
        // WhatsApps que él no mandó, y el tope dejaría de proteger al cliente para pasar a estorbar
        // la gestión. Ver la decisión regulatoria en la estrategia de cobranza.
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        long todayCount = attemptRepository.countManualByCaseIdAndAttemptedAtAfter(cmd.caseId(), startOfDay);
        if (todayCount >= properties.getMaxContactAttemptsPerDay()) {
            throw new InvalidCaseStateException("Max contact attempts per day (" +
                    properties.getMaxContactAttemptsPerDay() + ") already reached for case " + cmd.caseId());
        }

        ContactAttempt attempt = ContactAttempt.record(cmd.caseId(), cmd.channel(), cmd.result(), cmd.agentId());
        attemptRepository.save(attempt);
        log.info("ContactAttempt recorded caseId={} channel={} result={}", cmd.caseId(), cmd.channel(), cmd.result());
        eventPublisher.publishContactAttemptRegistered(attempt);
        return attempt;
    }
}
