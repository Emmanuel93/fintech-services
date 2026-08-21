package com.fintech.origination;

import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.infrastructure.job.DocumentsExpirationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class DocumentsExpirationJobTest {

    @Mock CreditApplicationRepository applicationRepository;

    DocumentsExpirationJob job;

    @BeforeEach
    void setUp() {
        job = new DocumentsExpirationJob(applicationRepository);
    }

    private CreditApplication appPendingDocumentsPastDeadline() {
        CreditApplication app = CreditApplication.start(UUID.randomUUID(), ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
        app.requestDocuments("analyst-1", "Comprobante de ingresos", Instant.now().minusSeconds(3600));
        return app;
    }

    @Test
    void expire_noneOverdue_doesNotSave() {
        given(applicationRepository.findPendingDocumentsPastDeadline(any(Instant.class)))
                .willReturn(List.of());

        job.expireStaleDocumentRequests();

        then(applicationRepository).should(never()).save(any());
    }

    @Test
    void expire_oneOverdue_cancels() {
        CreditApplication app = appPendingDocumentsPastDeadline();
        given(applicationRepository.findPendingDocumentsPastDeadline(any(Instant.class)))
                .willReturn(List.of(app));

        job.expireStaleDocumentRequests();

        ArgumentCaptor<CreditApplication> captor = ArgumentCaptor.forClass(CreditApplication.class);
        then(applicationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(ApplicationStatus.CANCELLED);
        assertThat(captor.getValue().getRejectionReason()).contains("Documentos no entregados");
    }

    @Test
    void expire_multipleOverdue_savesEach() {
        given(applicationRepository.findPendingDocumentsPastDeadline(any(Instant.class)))
                .willReturn(List.of(appPendingDocumentsPastDeadline(), appPendingDocumentsPastDeadline()));

        job.expireStaleDocumentRequests();

        then(applicationRepository).should(times(2)).save(any());
    }

    @Test
    void expireSingle_wrongStatus_doesNotPropagate_andDoesNotSave() {
        // PENDING_SCORING — expireForMissingDocuments() throws; the job must swallow it.
        CreditApplication invalid = CreditApplication.start(UUID.randomUUID(), ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);

        job.expireSingle(invalid);

        then(applicationRepository).should(never()).save(any());
    }
}
