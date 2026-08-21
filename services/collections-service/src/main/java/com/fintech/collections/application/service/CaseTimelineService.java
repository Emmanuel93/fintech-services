package com.fintech.collections.application.service;

import com.fintech.collections.application.port.in.CaseTimelineUseCase;
import com.fintech.collections.application.port.out.CollectionAgreementRepository;
import com.fintech.collections.application.port.out.ContactAttemptRepository;
import com.fintech.collections.application.port.out.PaymentPromiseRepository;
import com.fintech.collections.application.port.out.WriteOffRecordRepository;
import com.fintech.collections.domain.AgreementStatus;
import com.fintech.collections.domain.CollectionAgreement;
import com.fintech.collections.domain.ContactAttempt;
import com.fintech.collections.domain.PaymentPromise;
import com.fintech.collections.domain.WriteOffRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sólo lectura: no decide nada, reúne lo que ya se registró sobre un caso. */
@Service
@Transactional(readOnly = true)
public class CaseTimelineService implements CaseTimelineUseCase {

    private final ContactAttemptRepository attemptRepository;
    private final PaymentPromiseRepository promiseRepository;
    private final CollectionAgreementRepository agreementRepository;
    private final WriteOffRecordRepository writeOffRepository;

    public CaseTimelineService(ContactAttemptRepository attemptRepository,
                                PaymentPromiseRepository promiseRepository,
                                CollectionAgreementRepository agreementRepository,
                                WriteOffRecordRepository writeOffRepository) {
        this.attemptRepository   = attemptRepository;
        this.promiseRepository   = promiseRepository;
        this.agreementRepository = agreementRepository;
        this.writeOffRepository  = writeOffRepository;
    }

    @Override
    public List<ContactAttempt> contactAttempts(UUID caseId) {
        return attemptRepository.findByCaseId(caseId);
    }

    @Override
    public long contactAttemptsToday(UUID caseId) {
        // Mismo corte de día **y mismo filtro** que ContactService aplica al validar CT-03: sólo los
        // intentos de agente. Contar aquí también los automáticos hacía que la pantalla dijera «2 de
        // 3» a un gestor con el cupo intacto, por dos WhatsApps que él no mandó — y ése es el número
        // con el que decide si todavía puede llamar.
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        return attemptRepository.countManualByCaseIdAndAttemptedAtAfter(caseId, startOfDay);
    }

    @Override
    public List<PaymentPromise> paymentPromises(UUID caseId) {
        return promiseRepository.findByCaseId(caseId);
    }

    @Override
    public List<CollectionAgreement> agreements(UUID caseId) {
        return agreementRepository.findByCaseIdOrderByProposedAtDesc(caseId);
    }

    @Override
    public List<CollectionAgreement> agreementsAwaitingAuthorization() {
        return agreementRepository.findByStatusOrderByRespondedAtAsc(AgreementStatus.ACCEPTED);
    }

    @Override
    public Optional<WriteOffRecord> writeOffByAccount(UUID creditAccountId) {
        return writeOffRepository.findByCreditAccountId(creditAccountId);
    }
}
