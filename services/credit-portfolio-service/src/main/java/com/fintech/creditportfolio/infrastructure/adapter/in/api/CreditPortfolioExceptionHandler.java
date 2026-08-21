package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
class CreditPortfolioExceptionHandler {

    @ExceptionHandler(CreditAccountNotFoundException.class)
    ProblemDetail handleNotFound(CreditAccountNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/CREDIT_PORTFOLIO_NOT_FOUND"));
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/CREDIT_PORTFOLIO_INVALID_STATE"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/CREDIT_PORTFOLIO_BAD_REQUEST"));
        return pd;
    }
}
