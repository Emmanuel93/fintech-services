package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.DuplicateProspectException;
import com.fintech.origination.domain.PrivacyNoticeRequiredException;
import com.fintech.origination.domain.PromoterCodeNotResolvableException;
import com.fintech.origination.domain.ProspectNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
class OriginationExceptionHandler {

    @ExceptionHandler(DuplicateProspectException.class)
    ProblemDetail handleDuplicateProspect(DuplicateProspectException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(PrivacyNoticeRequiredException.class)
    ProblemDetail handlePrivacyNoticeRequired(PrivacyNoticeRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler({CreditApplicationNotFoundException.class, ProspectNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        String code = (ex instanceof com.fintech.shared.exception.DomainException de)
                ? de.getErrorCode() : "NOT_FOUND";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + code));
        return pd;
    }

    @ExceptionHandler(DuplicateActiveApplicationException.class)
    ProblemDetail handleDuplicateActiveApplication(DuplicateActiveApplicationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(CooldownActiveException.class)
    ProblemDetail handleCooldownActive(CooldownActiveException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    /** promoterCode que no corresponde a ningún distribuidor — se rechaza la solicitud (CM-07). */
    @ExceptionHandler(PromoterCodeNotResolvableException.class)
    ProblemDetail handlePromoterNotResolvable(PromoterCodeNotResolvableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    /** Covers illegal state transitions (e.g. approval on non-pending-review application). */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/ORIGINATION_INVALID_STATE_TRANSITION"));
        return pd;
    }

    /** Concurrent requests that race past the in-memory duplicate check and hit the DB unique index. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Operation would violate a data integrity constraint");
        pd.setType(URI.create("https://fintech.com/errors/ORIGINATION_CONFLICT"));
        return pd;
    }
}
