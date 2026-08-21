package com.fintech.collections.infrastructure.adapter.in.api;

import com.fintech.collections.application.ApproveWriteOffCommand;
import com.fintech.collections.application.CollectionsProperties;
import com.fintech.collections.application.CreatePaymentPromiseCommand;
import com.fintech.collections.application.ProposeAgreementCommand;
import com.fintech.collections.application.RecordContactAttemptCommand;
import com.fintech.collections.application.RequestWriteOffCommand;
import com.fintech.collections.application.port.in.*;
import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.DelinquencyBucket;
import com.fintech.collections.infrastructure.adapter.in.api.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/collections")
@Tag(name = "Collections", description = "Cobranza temprana, mora, acuerdos de cobranza y quebranto")
@SecurityRequirement(name = "bearerAuth")
class CollectionsController {

    private final GetCollectionCaseUseCase getCaseUseCase;
    private final CreatePaymentPromiseUseCase createPromiseUseCase;
    private final RecordContactAttemptUseCase recordContactUseCase;
    private final AgreementUseCase agreementUseCase;
    private final WriteOffUseCase writeOffUseCase;
    private final ListBureauReportsUseCase listBureauReportsUseCase;
    private final CaseTimelineUseCase timelineUseCase;
    private final CollectionsProperties properties;
    private final com.fintech.collections.application.service.CommunicationHoldService holdService;

    CollectionsController(GetCollectionCaseUseCase getCaseUseCase,
                           CreatePaymentPromiseUseCase createPromiseUseCase,
                           RecordContactAttemptUseCase recordContactUseCase,
                           AgreementUseCase agreementUseCase,
                           WriteOffUseCase writeOffUseCase,
                           ListBureauReportsUseCase listBureauReportsUseCase,
                           CaseTimelineUseCase timelineUseCase,
                           CollectionsProperties properties,
                           com.fintech.collections.application.service.CommunicationHoldService holdService) {
        this.holdService             = holdService;
        this.getCaseUseCase          = getCaseUseCase;
        this.createPromiseUseCase    = createPromiseUseCase;
        this.recordContactUseCase    = recordContactUseCase;
        this.agreementUseCase        = agreementUseCase;
        this.writeOffUseCase         = writeOffUseCase;
        this.listBureauReportsUseCase = listBureauReportsUseCase;
        this.timelineUseCase         = timelineUseCase;
        this.properties              = properties;
    }

    @Operation(summary = "Paged case search for the collections queue — all filters optional")
    @GetMapping("/cases")
    Page<CollectionCaseResponse> searchCases(@RequestParam(required = false) CaseStatus status,
                                              @RequestParam(required = false) DelinquencyBucket bucket,
                                              @RequestParam(required = false) String productType,
                                              @RequestParam(required = false) String assignedAgentId,
                                              @RequestParam(required = false) Integer minDaysDelinquent,
                                              Pageable pageable) {
        return getCaseUseCase.search(status, bucket, productType, assignedAgentId, minDaysDelinquent, pageable)
                .map(CollectionCaseResponse::from);
    }

    @Operation(summary = "Get a collection case by id")
    @GetMapping("/cases/{caseId}")
    ResponseEntity<CollectionCaseResponse> getCase(@PathVariable UUID caseId) {
        return ResponseEntity.ok(CollectionCaseResponse.from(getCaseUseCase.getById(caseId)));
    }

    @Operation(summary = "Contact attempts recorded for a case, with today's count against the CT-03 cap")
    @GetMapping("/cases/{caseId}/contact-attempts")
    ContactAttemptsResponse listContactAttempts(@PathVariable UUID caseId) {
        return new ContactAttemptsResponse(
                timelineUseCase.contactAttempts(caseId).stream().map(ContactAttemptResponse::from).toList(),
                timelineUseCase.contactAttemptsToday(caseId),
                properties.getMaxContactAttemptsPerDay());
    }

    @Operation(summary = "Communication holds — why automated dunning is silent on this case")
    @GetMapping("/cases/{caseId}/communication-holds")
    List<CommunicationHoldResponse> listHolds(@PathVariable UUID caseId) {
        Instant ahora = Instant.now();
        return holdService.history(caseId).stream()
                .map(h -> CommunicationHoldResponse.from(h, ahora))
                .toList();
    }

    @Operation(summary = "Payment promises recorded for a case")
    @GetMapping("/cases/{caseId}/payment-promises")
    List<PaymentPromiseResponse> listPromises(@PathVariable UUID caseId) {
        return timelineUseCase.paymentPromises(caseId).stream().map(PaymentPromiseResponse::from).toList();
    }

    @Operation(summary = "Agreement history for a case — includes rejected and expired")
    @GetMapping("/cases/{caseId}/agreements")
    List<CollectionAgreementResponse> listAgreements(@PathVariable UUID caseId) {
        return timelineUseCase.agreements(caseId).stream().map(CollectionAgreementResponse::from).toList();
    }

    @Operation(summary = "Agreements accepted by the debtor and awaiting authorization (maker-checker)")
    @GetMapping("/agreements/awaiting-authorization")
    List<CollectionAgreementResponse> listAwaitingAuthorization() {
        return timelineUseCase.agreementsAwaitingAuthorization().stream()
                .map(CollectionAgreementResponse::from).toList();
    }

    @Operation(summary = "The write-off record of an account, if it was written off")
    @GetMapping("/accounts/{creditAccountId}/write-off")
    ResponseEntity<WriteOffRecordResponse> getWriteOff(@PathVariable UUID creditAccountId) {
        return timelineUseCase.writeOffByAccount(creditAccountId)
                .map(WriteOffRecordResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Los parámetros con los que el dominio valida, para que la pantalla pueda deshabilitar en vez
     * de dejar intentar y fallar. Se exponen porque viven en application.yml: hardcodearlos en el
     * cliente los desincroniza en cuanto ops cambie una variable de entorno, y el usuario se entera
     * por un 422 que no esperaba.
     */
    @Operation(summary = "The seven configured collection limits the UI validates against")
    @GetMapping("/config")
    CollectionsConfigResponse config() {
        return CollectionsConfigResponse.from(properties);
    }

    @Operation(summary = "Get the active collection case for a credit account")
    @GetMapping("/accounts/{creditAccountId}/case")
    ResponseEntity<CollectionCaseResponse> getCaseByAccount(@PathVariable UUID creditAccountId) {
        return ResponseEntity.ok(CollectionCaseResponse.from(getCaseUseCase.getByCreditAccountId(creditAccountId)));
    }

    @Operation(summary = "Record a payment promise for a case")
    @PostMapping("/cases/{caseId}/payment-promises")
    @ResponseStatus(HttpStatus.CREATED)
    PaymentPromiseResponse createPromise(@PathVariable UUID caseId,
                                          @Valid @RequestBody CreatePaymentPromiseRequest req,
                                          Authentication auth) {
        var cmd = new CreatePaymentPromiseCommand(caseId, req.amount(), req.promisedDate(), auth.getName());
        return PaymentPromiseResponse.from(createPromiseUseCase.create(cmd));
    }

    @Operation(summary = "Record a contact attempt for a case (CT-01..CT-04)")
    @PostMapping("/cases/{caseId}/contact-attempts")
    @ResponseStatus(HttpStatus.CREATED)
    ContactAttemptResponse recordContactAttempt(@PathVariable UUID caseId,
                                                 @Valid @RequestBody RecordContactAttemptRequest req,
                                                 Authentication auth) {
        var cmd = new RecordContactAttemptCommand(caseId, req.channel(), req.result(), auth.getName());
        return ContactAttemptResponse.from(recordContactUseCase.record(cmd));
    }

    @Operation(summary = "Propose a collection agreement — RESTRUCTURE or QUITA_PARCIAL")
    @PostMapping("/cases/{caseId}/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    CollectionAgreementResponse proposeAgreement(@PathVariable UUID caseId,
                                                  @Valid @RequestBody ProposeAgreementRequest req) {
        var cmd = new ProposeAgreementCommand(caseId, req.type(), req.forgivenAmount(), req.newTerms());
        return CollectionAgreementResponse.from(agreementUseCase.propose(cmd));
    }

    @Operation(summary = "Debtor accepts a proposed agreement")
    @PutMapping("/agreements/{agreementId}/accept")
    CollectionAgreementResponse acceptAgreement(@PathVariable UUID agreementId) {
        return CollectionAgreementResponse.from(agreementUseCase.accept(agreementId));
    }

    @Operation(summary = "Debtor rejects a proposed agreement")
    @PutMapping("/agreements/{agreementId}/reject")
    CollectionAgreementResponse rejectAgreement(@PathVariable UUID agreementId) {
        return CollectionAgreementResponse.from(agreementUseCase.reject(agreementId));
    }

    @Operation(summary = "Authorize (and execute) an accepted agreement — AG-02")
    @PutMapping("/agreements/{agreementId}/authorize")
    CollectionAgreementResponse authorizeAgreement(@PathVariable UUID agreementId,
                                                    @Valid @RequestBody AuthorizeAgreementRequest req) {
        return CollectionAgreementResponse.from(
                agreementUseCase.authorize(agreementId, req.authorizedBy(), req.authorizationRef()));
    }

    @Operation(summary = "Request a write-off (quita total) — intent only, WO-01")
    @PostMapping("/cases/{caseId}/request-write-off")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestWriteOff(@PathVariable UUID caseId,
                          @Valid @RequestBody RequestWriteOffRequest req,
                          Authentication auth) {
        writeOffUseCase.request(new RequestWriteOffCommand(caseId, req.reason(), auth.getName()));
    }

    @Operation(summary = "Approve and execute a write-off (quita total) — WO-02")
    @PostMapping("/cases/{caseId}/write-offs")
    @ResponseStatus(HttpStatus.CREATED)
    WriteOffRecordResponse approveWriteOff(@PathVariable UUID caseId,
                                            @Valid @RequestBody ApproveWriteOffRequest req) {
        var cmd = new ApproveWriteOffCommand(caseId, req.reason(), req.authorizedBy(), req.authorizationRef());
        return WriteOffRecordResponse.from(writeOffUseCase.approve(cmd));
    }

    @Operation(summary = "List bureau reports pending submission (PENDING or FAILED) — CC-07")
    @GetMapping("/bureau-reports")
    List<BureauReportResponse> listBureauReports() {
        return listBureauReportsUseCase.listPending().stream()
                .map(BureauReportResponse::from)
                .toList();
    }
}
