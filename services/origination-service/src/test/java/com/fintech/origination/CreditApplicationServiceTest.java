package com.fintech.origination;

import com.fintech.origination.application.ApplyScoringDecisionCommand;
import com.fintech.origination.application.RecordApprovalDecisionCommand;
import com.fintech.origination.application.RequestDocumentsCommand;
import com.fintech.origination.application.StartCreditApplicationCommand;
import com.fintech.origination.application.StartCreditApplicationResult;
import com.fintech.origination.application.port.out.ApplicationEventPublisher;
import com.fintech.origination.application.port.out.CreditApplicationRepository;
import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.application.port.out.ScoreRequestPublisher;
import com.fintech.origination.application.service.CreditApplicationService;
import com.fintech.origination.domain.ApplicationStatus;
import com.fintech.origination.domain.ApprovalFlow;
import com.fintech.origination.domain.ChannelType;
import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.CreditApplication;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.Gender;
import com.fintech.origination.domain.ProductType;
import com.fintech.origination.domain.PromoterCodeNotResolvableException;
import com.fintech.origination.domain.Prospect;
import com.fintech.origination.domain.ProspectAddress;
import com.fintech.origination.domain.ProspectNotFoundException;
import com.fintech.origination.domain.ProspectType;
import com.fintech.origination.domain.event.DocumentsRequestedEvent;
import com.fintech.origination.domain.event.ScoreRequestedEvent;
import com.fintech.origination.infrastructure.config.OriginationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class CreditApplicationServiceTest {

    @Mock CreditApplicationRepository applicationRepository;
    @Mock ProspectRepository prospectRepository;
    @Mock ScoreRequestPublisher scoreRequestPublisher;
    @Mock ApplicationEventPublisher applicationEventPublisher;
    @Mock com.fintech.origination.application.port.out.PromoterResolver promoterResolver;

    OriginationProperties properties;
    CreditApplicationService service;

    final UUID prospectId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties = new OriginationProperties();
        // default committeeAmountThreshold = 500,000; cooldownDays = 90
        service = new CreditApplicationService(
                applicationRepository, prospectRepository,
                scoreRequestPublisher, applicationEventPublisher, promoterResolver, properties);
    }

    private Prospect onboardedProspect() {
        ProspectAddress addr = new ProspectAddress(
                "Av. Insurgentes Sur", "1602", null, "Crédito Constructor",
                null, "CDMX", "CDMX", "03940", "MX");
        return Prospect.create(
                prospectId, ProspectType.INDIVIDUAL,
                "Carlos", "Ramírez", "Torres",
                "RATC850320HDFMRL09", null,
                LocalDate.of(1985, 3, 20), Gender.MALE, "Ciudad de México",
                "+5215512345678", "carlos@example.com",
                addr, ChannelType.MOBILE_APP,
                true, true, List.of(), 30);
    }

    private StartCreditApplicationCommand command() {
        return new StartCreditApplicationCommand(
                prospectId, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000.00"), 12, "corr-app-001", null);
    }

    private void stubNoCooldown() {
        given(applicationRepository.findMostRecentRejectionAfter(any(), any(), any()))
                .willReturn(Optional.empty());
    }

    private CreditApplication appUnderReview() {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
        return app;
    }

    // ── DS-4 — resolución de promoterCode (CM-07) ─────────────────────────────

    @Test
    void start_humanPromoterCode_resolvedToDistributorPartyId() {
        UUID distId = UUID.randomUUID();
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        stubNoCooldown();
        given(promoterResolver.resolveDistributor("DIST0001")).willReturn(Optional.of(distId));
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.start(new StartCreditApplicationCommand(prospectId, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000"), 12, "corr", "DIST0001"));

        ArgumentCaptor<CreditApplication> captor = ArgumentCaptor.forClass(CreditApplication.class);
        then(applicationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getPromoterCode()).isEqualTo(distId.toString());
    }

    @Test
    void start_unresolvablePromoterCode_rejects_CM07() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        stubNoCooldown();
        given(promoterResolver.resolveDistributor("BADCODE")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(new StartCreditApplicationCommand(prospectId,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12, "corr", "BADCODE")))
                .isInstanceOf(PromoterCodeNotResolvableException.class);
        then(applicationRepository).should(never()).save(any());
    }

    @Test
    void start_uuidPromoterCode_passesThroughWithoutResolving() {
        UUID promoter = UUID.randomUUID();
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        stubNoCooldown();
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.start(new StartCreditApplicationCommand(prospectId, ProductType.PERSONAL_LOAN,
                new BigDecimal("50000"), 12, "corr", promoter.toString()));

        then(promoterResolver).should(never()).resolveDistributor(any());
        ArgumentCaptor<CreditApplication> captor = ArgumentCaptor.forClass(CreditApplication.class);
        then(applicationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getPromoterCode()).isEqualTo(promoter.toString());
    }

    // ── E6 — documentos pendientes ────────────────────────────────────────────

    @Test
    void requestDocuments_transitionsToPendingDocuments_setsDeadline_andPublishesEvent() {
        CreditApplication app = appUnderReview();
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        service.requestDocuments(new RequestDocumentsCommand(
                appId, "analyst-1", "Comprobante de ingresos", "corr-1"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_DOCUMENTS);
        assertThat(app.getDocumentsDeadline()).isNotNull();
        assertThat(app.getDocumentsNote()).isEqualTo("Comprobante de ingresos");
        then(applicationRepository).should().save(app);

        ArgumentCaptor<DocumentsRequestedEvent> ev = ArgumentCaptor.forClass(DocumentsRequestedEvent.class);
        then(applicationEventPublisher).should().publishDocumentsRequested(ev.capture());
        assertThat(ev.getValue().getApplicationId()).isEqualTo(appId);
        assertThat(ev.getValue().getNote()).isEqualTo("Comprobante de ingresos");
        assertThat(ev.getValue().getDeadline()).isNotNull();
    }

    @Test
    void requestDocuments_appNotFound_throws() {
        UUID appId = UUID.randomUUID();
        given(applicationRepository.findById(appId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestDocuments(
                new RequestDocumentsCommand(appId, "analyst-1", "docs", null)))
                .isInstanceOf(CreditApplicationNotFoundException.class);
        then(applicationEventPublisher).should(never()).publishDocumentsRequested(any());
    }

    @Test
    void markDocumentsReceived_resumesManualReview() {
        CreditApplication app = appUnderReview();
        app.requestDocuments("analyst-1", "docs", Instant.now().plusSeconds(3600));
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        service.markDocumentsReceived(appId);

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_MANUAL_REVIEW);
        assertThat(app.getDocumentsDeadline()).isNull();
        then(applicationRepository).should().save(app);
    }

    // ── start ───────────────────────────────────────────────────────────────

    @Test
    void start_validCommand_returnsApplicationStarted_pendingScoring() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(prospectId, ProductType.PERSONAL_LOAN))
                .willReturn(false);
        stubNoCooldown();
        given(applicationRepository.save(any(CreditApplication.class))).willAnswer(inv -> inv.getArgument(0));

        StartCreditApplicationResult result = service.start(command());

        assertThat(result).isInstanceOf(StartCreditApplicationResult.ApplicationStarted.class);
        var started = (StartCreditApplicationResult.ApplicationStarted) result;
        assertThat(started.applicationId()).isNotNull();
        assertThat(started.prospectId()).isEqualTo(prospectId);
        assertThat(started.productType()).isEqualTo(ProductType.PERSONAL_LOAN);
        assertThat(started.status()).isEqualTo(ApplicationStatus.PENDING_SCORING);
    }

    @Test
    void start_publishesScoreRequested_withProductAndProspectType() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        stubNoCooldown();
        given(applicationRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ScoreRequestedEvent> captor = ArgumentCaptor.forClass(ScoreRequestedEvent.class);
        willDoNothing().given(scoreRequestPublisher).publish(captor.capture());

        service.start(command());

        ScoreRequestedEvent event = captor.getValue();
        assertThat(event.getProspectId()).isEqualTo(prospectId);
        assertThat(event.getProspectType()).isEqualTo(ProspectType.INDIVIDUAL);
        assertThat(event.getProductType()).isEqualTo(ProductType.PERSONAL_LOAN);
        assertThat(event.getRequestedAmount()).isEqualByComparingTo("50000.00");
        assertThat(event.getRequestedTerm()).isEqualTo(12);
        assertThat(event.getApplicationId()).isNotNull();
        assertThat(event.getCorrelationId()).isEqualTo("corr-app-001");
    }

    @Test
    void start_prospectNotFound_throws_noSaveNoPublish() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(command()))
                .isInstanceOf(ProspectNotFoundException.class);

        then(applicationRepository).should(never()).save(any());
        then(scoreRequestPublisher).should(never()).publish(any());
    }

    @Test
    void start_duplicateActiveApplication_throws_noSaveNoPublish() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(prospectId, ProductType.PERSONAL_LOAN))
                .willReturn(true);

        assertThatThrownBy(() -> service.start(command()))
                .isInstanceOf(DuplicateActiveApplicationException.class);

        then(applicationRepository).should(never()).save(any());
        then(scoreRequestPublisher).should(never()).publish(any());
    }

    @Test
    void start_cooldownActive_throws_noSaveNoPublish() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        CreditApplication rejected = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
        rejected.reject("Score alto", "ALTO");
        given(applicationRepository.findMostRecentRejectionAfter(any(), any(), any()))
                .willReturn(Optional.of(rejected));

        assertThatThrownBy(() -> service.start(command()))
                .isInstanceOf(CooldownActiveException.class);

        then(applicationRepository).should(never()).save(any());
        then(scoreRequestPublisher).should(never()).publish(any());
    }

    @Test
    void start_inheritsProspectTypeFromProspect() {
        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        stubNoCooldown();
        ArgumentCaptor<CreditApplication> captor = ArgumentCaptor.forClass(CreditApplication.class);
        given(applicationRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

        service.start(command());

        assertThat(captor.getValue().getProspectType()).isEqualTo(ProspectType.INDIVIDUAL);
        assertThat(captor.getValue().getStatus()).isEqualTo(ApplicationStatus.PENDING_SCORING);
    }

    // ── queries ───────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsApplication() {
        UUID appId = UUID.randomUUID();
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("1000"), 6);
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        assertThat(service.getById(appId)).isSameAs(app);
    }

    @Test
    void getById_notFound_throws() {
        UUID appId = UUID.randomUUID();
        given(applicationRepository.findById(appId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(appId))
                .isInstanceOf(CreditApplicationNotFoundException.class);
    }

    @Test
    void findByProspectId_delegatesToRepository() {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.REVOLVING_LINE, null, null);
        given(applicationRepository.findByProspectId(prospectId)).willReturn(List.of(app));

        assertThat(service.findByProspectId(prospectId)).containsExactly(app);
    }

    @Test
    void search_delegatesToRepository() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<CreditApplication> page = new PageImpl<>(List.of());
        given(applicationRepository.search(any(), any(), any(), any(), any(), any(), any(), eq(pageable)))
                .willReturn(page);

        assertThat(service.search(null, null, null, null, null, null, null, pageable)).isSameAs(page);
    }

    // ── Phase C: apply scoring decision ─────────────────────────────────────────

    private CreditApplication pendingApplication() {
        return CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("50000"), 12);
    }

    private ApplyScoringDecisionCommand decision(String decision, String risk) {
        return new ApplyScoringDecisionCommand(null, prospectId, ProductType.PERSONAL_LOAN,
                decision, risk, 430, UUID.randomUUID(), "corr-dec");
    }

    @Test
    void apply_autoApproved_transitionsToApproved_setsFlowAutomatic() {
        CreditApplication app = pendingApplication();
        given(applicationRepository.findActiveByProspectIdAndProductType(prospectId, ProductType.PERSONAL_LOAN))
                .willReturn(Optional.of(app));

        service.apply(decision("AUTO_APPROVED", "BAJO"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getRiskLevel()).isEqualTo("BAJO");
        assertThat(app.getApprovalFlow()).isEqualTo(ApprovalFlow.AUTOMATIC.name());
        assertThat(app.getDecidedBy()).isEqualTo("SYSTEM");
        then(applicationRepository).should().save(app);
        then(applicationEventPublisher).should().publishApplicationApproved(any());
    }

    @Test
    void apply_manualReview_belowThreshold_transitionsToUnderManualReview() {
        // 50_000 < 500_000 threshold → MANUAL
        CreditApplication app = pendingApplication();
        given(applicationRepository.findActiveByProspectIdAndProductType(any(), any()))
                .willReturn(Optional.of(app));

        service.apply(decision("MANUAL_REVIEW", "MEDIO"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.UNDER_MANUAL_REVIEW);
        assertThat(app.getApprovalFlow()).isEqualTo(ApprovalFlow.MANUAL.name());
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void apply_manualReview_aboveThreshold_transitionsToCommitteeReview() {
        // 600_000 > 500_000 threshold → COMMITTEE
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("600000"), 24);
        given(applicationRepository.findActiveByProspectIdAndProductType(any(), any()))
                .willReturn(Optional.of(app));

        service.apply(decision("MANUAL_REVIEW", "MEDIO"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.COMMITTEE_REVIEW);
        assertThat(app.getApprovalFlow()).isEqualTo(ApprovalFlow.COMMITTEE.name());
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    void apply_rejected_transitionsToRejected_setsRejectedAt_publishesEvent() {
        CreditApplication app = pendingApplication();
        given(applicationRepository.findActiveByProspectIdAndProductType(any(), any()))
                .willReturn(Optional.of(app));

        service.apply(decision("REJECTED", "ALTO"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getRejectionReason()).isNotBlank();
        assertThat(app.getRejectedAt()).isNotNull();
        then(applicationEventPublisher).should().publishApplicationRejected(any());
    }

    @Test
    void apply_noActiveApplication_skips_noSave() {
        given(applicationRepository.findActiveByProspectIdAndProductType(any(), any()))
                .willReturn(Optional.empty());

        service.apply(decision("AUTO_APPROVED", "BAJO"));

        then(applicationRepository).should(never()).save(any());
    }

    @Test
    void apply_withApplicationId_usesDirectLookup() {
        CreditApplication app = pendingApplication();
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        service.apply(new ApplyScoringDecisionCommand(appId, prospectId, ProductType.PERSONAL_LOAN,
                "AUTO_APPROVED", "BAJO", 430, UUID.randomUUID(), "corr"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        then(applicationRepository).should().save(app);
        then(applicationRepository).should(never()).findActiveByProspectIdAndProductType(any(), any());
    }

    @Test
    void apply_unknownDecision_noSave() {
        CreditApplication app = pendingApplication();
        given(applicationRepository.findActiveByProspectIdAndProductType(any(), any()))
                .willReturn(Optional.of(app));

        service.apply(decision("WAT", "BAJO"));

        then(applicationRepository).should(never()).save(any());
        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.PENDING_SCORING);
    }

    // ── Phase H: manual approval decision ───────────────────────────────────────

    @Test
    void record_approve_fromManualReview_transitionsToApproved_publishesEvent() {
        CreditApplication app = pendingApplication();
        app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        service.record(new RecordApprovalDecisionCommand(appId, "underwriter-001", true, null));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getDecidedBy()).isEqualTo("underwriter-001");
        then(applicationRepository).should().save(app);
        then(applicationEventPublisher).should().publishApplicationApproved(any());
    }

    @Test
    void record_approve_fromCommitteeReview_transitionsToApproved() {
        CreditApplication app = CreditApplication.start(prospectId, ProspectType.INDIVIDUAL,
                ProductType.PERSONAL_LOAN, new BigDecimal("800000"), 36);
        app.sendToCommitteeReview("MEDIO", "MANUAL_REVIEW");
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        service.record(new RecordApprovalDecisionCommand(appId, "COMMITTEE", true, null));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        assertThat(app.getDecidedBy()).isEqualTo("COMMITTEE");
    }

    @Test
    void record_reject_fromManualReview_transitionsToRejected_setsRejectedAt() {
        CreditApplication app = pendingApplication();
        app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        service.record(new RecordApprovalDecisionCommand(appId, "underwriter-002", false,
                "Capacidad de pago insuficiente para el monto solicitado"));

        assertThat(app.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(app.getRejectedAt()).isNotNull();
        assertThat(app.getRejectionReason()).containsIgnoringCase("capacidad de pago");
        then(applicationEventPublisher).should().publishApplicationRejected(any());
    }

    @Test
    void record_notFound_throws() {
        UUID missing = UUID.randomUUID();
        given(applicationRepository.findById(missing)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(
                new RecordApprovalDecisionCommand(missing, "uw", true, null)))
                .isInstanceOf(CreditApplicationNotFoundException.class);
    }

    @Test
    void record_wrongStatus_throwsIllegalState() {
        CreditApplication app = pendingApplication(); // PENDING_SCORING, not awaiting human decision
        UUID appId = app.getApplicationId();
        given(applicationRepository.findById(appId)).willReturn(Optional.of(app));

        assertThatThrownBy(() -> service.record(
                new RecordApprovalDecisionCommand(appId, "uw", true, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── Thread-safety ────────────────────────────────────────────────────────────

    /**
     * Verifies that CreditApplicationService is safe for concurrent invocations.
     * The service is a Spring singleton — multiple threads must not corrupt shared state
     * (there is none: the service has no mutable fields). Under concurrent load, each
     * invocation must either succeed or throw an expected domain exception, never an
     * unexpected error (NPE, ClassCastException, etc.).
     *
     * Note: the real OA-03 uniqueness guarantee is enforced by the DB partial unique index.
     * This test verifies the service-level behavior when the check passes in all threads
     * (race window before the DB constraint fires) — each thread gets a valid result.
     */
    @Test
    void start_concurrentCalls_serviceRemainsStable_noUnexpectedExceptions()
            throws InterruptedException {

        given(prospectRepository.findById(prospectId)).willReturn(Optional.of(onboardedProspect()));
        given(applicationRepository.existsActiveByProspectIdAndProductType(any(), any())).willReturn(false);
        stubNoCooldown();
        given(applicationRepository.save(any(CreditApplication.class))).willAnswer(inv -> inv.getArgument(0));
        willDoNothing().given(scoreRequestPublisher).publish(any());

        int threads = 16;
        CountDownLatch ready  = new CountDownLatch(threads);
        CountDownLatch gate   = new CountDownLatch(1);
        CountDownLatch done   = new CountDownLatch(threads);
        AtomicInteger  successes       = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    gate.await();
                    service.start(command());
                    successes.incrementAndGet();
                } catch (DuplicateActiveApplicationException | CooldownActiveException expected) {
                    // domain exceptions are acceptable — not a thread-safety failure
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        gate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpected)
                .as("unexpected exceptions under concurrent load: %s", unexpected)
                .isEmpty();
        assertThat(successes.get()).isGreaterThan(0);
    }

    /**
     * Verifies that concurrent record() calls on different applications do not interfere.
     * Each thread processes a distinct applicationId — no shared mutable state at the
     * service level should cause cross-thread corruption.
     */
    @Test
    void record_concurrentCallsDifferentApplications_allSucceed()
            throws InterruptedException {

        int threads = 8;
        List<CreditApplication> apps = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            CreditApplication app = pendingApplication();
            app.sendToManualReview("MEDIO", "MANUAL_REVIEW");
            apps.add(app);
            given(applicationRepository.findById(app.getApplicationId()))
                    .willReturn(Optional.of(app));
        }

        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger successes   = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (CreditApplication app : apps) {
            executor.submit(() -> {
                try {
                    gate.await();
                    service.record(new RecordApprovalDecisionCommand(
                            app.getApplicationId(), "underwriter-concurrent", true, null));
                    successes.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        gate.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(unexpected)
                .as("unexpected exceptions: %s", unexpected)
                .isEmpty();
        assertThat(successes.get()).isEqualTo(threads);

        for (CreditApplication app : apps) {
            assertThat(app.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
        }
    }
}
