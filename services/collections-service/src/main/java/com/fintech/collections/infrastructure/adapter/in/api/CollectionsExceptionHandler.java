package com.fintech.collections.infrastructure.adapter.in.api;

import com.fintech.collections.domain.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class CollectionsExceptionHandler {

    @ExceptionHandler(CollectionCaseNotFoundException.class)
    ProblemDetail handleCaseNotFound(CollectionCaseNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(AgreementNotFoundException.class)
    ProblemDetail handleAgreementNotFound(AgreementNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidCaseStateException.class)
    ProblemDetail handleInvalidCaseState(InvalidCaseStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidAgreementStateException.class)
    ProblemDetail handleInvalidAgreementState(InvalidAgreementStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(ForgivenessLimitExceededException.class)
    ProblemDetail handleForgivenessLimitExceeded(ForgivenessLimitExceededException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    private ProblemDetail problem(HttpStatus status, String message, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        return pd;
    }
}
