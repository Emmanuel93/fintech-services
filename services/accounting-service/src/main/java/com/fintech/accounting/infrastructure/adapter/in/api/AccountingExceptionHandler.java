package com.fintech.accounting.infrastructure.adapter.in.api;

import com.fintech.accounting.domain.PostingRuleNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class AccountingExceptionHandler {

    @ExceptionHandler(PostingRuleNotFoundException.class)
    ProblemDetail handlePostingRuleNotFound(PostingRuleNotFoundException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    private ProblemDetail problem(HttpStatus status, String message, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        return pd;
    }
}
