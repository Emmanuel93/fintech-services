package com.fintech.configuration.infrastructure.adapter.in.api;

import com.fintech.configuration.domain.ConfigParameterNotFoundException;
import com.fintech.configuration.domain.DuplicateActiveParameterException;
import com.fintech.configuration.domain.InvalidConfigStateTransitionException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class ConfigExceptionHandler {

    @ExceptionHandler(ConfigParameterNotFoundException.class)
    ProblemDetail handleNotFound(ConfigParameterNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        pd.setProperty("errorCode", ex.getErrorCode());
        return pd;
    }

    @ExceptionHandler(InvalidConfigStateTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidConfigStateTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        pd.setProperty("errorCode", ex.getErrorCode());
        return pd;
    }

    @ExceptionHandler(DuplicateActiveParameterException.class)
    ProblemDetail handleDuplicate(DuplicateActiveParameterException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        pd.setProperty("errorCode", ex.getErrorCode());
        return pd;
    }
}
