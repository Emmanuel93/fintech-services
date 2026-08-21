package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.CollectionAgreement;
import com.fintech.collections.domain.ContactAttempt;
import com.fintech.collections.domain.PaymentPromise;
import com.fintech.collections.domain.WriteOffRecord;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface CollectionsEventPublisher {

    void publishPreDueReminderTriggered(UUID creditAccountId, UUID obligorPartyId,
                                         LocalDate dueDate, BigDecimal installmentAmount);

    void publishCollectionCaseCreated(com.fintech.collections.domain.CollectionCase collectionCase);

    void publishCollectionCaseEscalated(com.fintech.collections.domain.CollectionCase collectionCase,
                                         String previousBucket);

    void publishContactAttemptRegistered(ContactAttempt attempt);

    /**
     * Pide que se le escriba al deudor en el escalón de tono indicado.
     *
     * <p>Cobranza no envía: notifications elige canal y plantilla. Lo que aquí se decide es a quién
     * toca hoy y con qué tono, que es lo único que depende del estado del caso.
     */
    void publishDunningRequested(com.fintech.collections.domain.CollectionCase collectionCase,
                                 com.fintech.collections.domain.DunningStep step);

    /**
     * Se le agradece a quien cumplió.
     *
     * <p>Va aparte de la cadencia porque no es cobranza: es el único mensaje del módulo que no pide
     * nada. Mezclarlo con los escalones haría que el freno por promesa cumplida lo silenciara,
     * justo al que hay que mandar.
     */
    void publishPaymentThanks(com.fintech.collections.domain.CollectionCase collectionCase,
                              java.math.BigDecimal amount);

    void publishPaymentPromiseMade(PaymentPromise promise);

    void publishPaymentPromiseBroken(PaymentPromise promise);

    void publishCollectionAgreementProposed(CollectionAgreement agreement);

    void publishCollectionAgreementExecuted(CollectionAgreement agreement);

    void publishWriteOffRequested(UUID caseId, UUID creditAccountId, BigDecimal totalDebt,
                                   int daysDelinquent, String reason, String requestedBy);

    void publishWriteOffExecuted(WriteOffRecord record);

    void publishBureauReportSubmitted(com.fintech.collections.domain.BureauReport report);

    void publishRecoveryPaymentApplied(UUID writeOffId, UUID creditAccountId,
                                        BigDecimal recoveredAmount, String paymentMethod);
}
