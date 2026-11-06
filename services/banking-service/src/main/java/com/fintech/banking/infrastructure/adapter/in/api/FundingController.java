package com.fintech.banking.infrastructure.adapter.in.api;

import com.fintech.banking.application.service.FundingService;
import com.fintech.banking.application.service.FundingService.CapitalCommand;
import com.fintech.banking.application.service.FundingService.CreditLineCommand;
import com.fintech.banking.application.service.FundingService.MovementCommand;
import com.fintech.banking.domain.FundingMovement;
import com.fintech.banking.domain.FundingSource;
import com.fintech.banking.domain.FundingSource.CounterpartyType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Funding: the capital paid in and the lines drawn. Recording is treasury's (ADMIN, FINANCE); reading it is also the
 * auditor's and the operations supervisor's.
 */
@RestController
@RequestMapping("/api/v1/funding")
class FundingController {

    private final FundingService funding;

    FundingController(FundingService funding) {
        this.funding = funding;
    }

    record CapitalRequest(String shareholderName, String rfc, String minutesReference, UUID bankAccountId,
                          BigDecimal subscribedAmount, LocalDate agreedOn) {}

    record CreditLineRequest(CounterpartyType lenderType, String lenderName, String rfc, String agreementReference,
                             UUID bankAccountId, BigDecimal authorizedAmount, BigDecimal annualRate,
                             BigDecimal openingFee, LocalDate startDate, LocalDate maturityDate) {}

    record MovementRequest(FundingMovement.Type type, BigDecimal amount, LocalDate valueDate, String reference) {}

    record SourceResponse(UUID fundingSourceId, String kind, String counterpartyType, String counterpartyName,
                          String counterpartyRfc, String contractReference, UUID bankAccountId,
                          BigDecimal authorizedAmount, BigDecimal annualRate, BigDecimal openingFee,
                          boolean openingFeePaid, LocalDate startDate, LocalDate maturityDate, String status,
                          BigDecimal contributed, BigDecimal principal, BigDecimal interestPayable,
                          LocalDate accruedThrough, BigDecimal available) {
        static SourceResponse from(FundingSource s) {
            BigDecimal used = s.getKind() == FundingSource.Kind.CAPITAL ? s.getContributed() : s.getPrincipal();
            return new SourceResponse(s.getId(), s.getKind().name(), s.getCounterpartyType(), s.getCounterpartyName(),
                    s.getCounterpartyRfc(), s.getContractReference(), s.getBankAccountId(), s.getAuthorizedAmount(),
                    s.getAnnualRate(), s.getOpeningFee(), s.isOpeningFeePaid(), s.getStartDate(), s.getMaturityDate(),
                    s.getStatus(), s.getContributed(), s.getPrincipal(), s.getInterestPayable(), s.getAccruedThrough(),
                    s.getAuthorizedAmount().subtract(used));
        }
    }

    record MovementResponse(UUID fundingMovementId, UUID fundingSourceId, String type, BigDecimal amount,
                            LocalDate valueDate, LocalDate accruedFrom, LocalDate accruedThrough, String reference,
                            BigDecimal contributedAfter, BigDecimal principalAfter, BigDecimal interestPayableAfter,
                            String recordedBy, Instant recordedAt) {
        static MovementResponse from(FundingMovement m) {
            return new MovementResponse(m.getId(), m.getFundingSourceId(), m.getType().name(), m.getAmount(),
                    m.getValueDate(), m.getAccruedFrom(), m.getAccruedThrough(), m.getReference(),
                    m.getContributedAfter(), m.getPrincipalAfter(), m.getInterestPayableAfter(), m.getRecordedBy(),
                    m.getRecordedAt());
        }
    }

    @GetMapping("/sources")
    List<SourceResponse> sources() {
        return funding.sources().stream().map(SourceResponse::from).toList();
    }

    @GetMapping("/sources/{id}/movements")
    List<MovementResponse> movements(@PathVariable UUID id) {
        return funding.movements(id).stream().map(MovementResponse::from).toList();
    }

    @PostMapping("/sources/capital")
    ResponseEntity<SourceResponse> capital(@RequestBody CapitalRequest r) {
        FundingSource s = funding.registerCapital(new CapitalCommand(r.shareholderName(), r.rfc(), r.minutesReference(),
                r.bankAccountId(), r.subscribedAmount(), r.agreedOn()), user());
        return ResponseEntity.status(HttpStatus.CREATED).body(SourceResponse.from(s));
    }

    @PostMapping("/sources/credit-lines")
    ResponseEntity<SourceResponse> creditLine(@RequestBody CreditLineRequest r) {
        FundingSource s = funding.registerCreditLine(new CreditLineCommand(r.lenderType(), r.lenderName(), r.rfc(),
                r.agreementReference(), r.bankAccountId(), r.authorizedAmount(), r.annualRate(), r.openingFee(),
                r.startDate(), r.maturityDate()), user());
        return ResponseEntity.status(HttpStatus.CREATED).body(SourceResponse.from(s));
    }

    @PostMapping("/sources/{id}/movements")
    ResponseEntity<List<MovementResponse>> record(@PathVariable UUID id, @RequestBody MovementRequest r) {
        var made = funding.record(id, new MovementCommand(r.type(), r.amount(), r.valueDate(), r.reference()), user());
        return ResponseEntity.status(HttpStatus.CREATED).body(made.stream().map(MovementResponse::from).toList());
    }

    /** Accrues every active line's interest through a day; what a month-end close needs before it reconciles. */
    @PostMapping("/accruals")
    List<MovementResponse> accrue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate through) {
        return funding.accrueAll(through, user()).stream().map(MovementResponse::from).toList();
    }

    private static String user() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : String.valueOf(auth.getPrincipal());
    }
}
