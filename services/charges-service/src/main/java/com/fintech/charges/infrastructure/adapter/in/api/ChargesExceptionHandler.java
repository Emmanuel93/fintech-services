package com.fintech.charges.infrastructure.adapter.in.api;

import com.fintech.charges.domain.AccrualScheduleNotFoundException;
import com.fintech.charges.domain.ChargeRecordNotFoundException;
import com.fintech.charges.domain.InvalidChargeStateException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class ChargesExceptionHandler {

    @ExceptionHandler(AccrualScheduleNotFoundException.class)
    ProblemDetail handleScheduleNotFound(AccrualScheduleNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(ChargeRecordNotFoundException.class)
    ProblemDetail handleChargeNotFound(ChargeRecordNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(InvalidChargeStateException.class)
    ProblemDetail handleInvalidState(InvalidChargeStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }
}
