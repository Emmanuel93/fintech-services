package com.fintech.payments.infrastructure.adapter.in.api;

import com.fintech.payments.domain.InsufficientBalanceException;
import com.fintech.payments.domain.InvalidPaymentStateException;
import com.fintech.payments.domain.PaymentOrderNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class PaymentsExceptionHandler {

    @ExceptionHandler(PaymentOrderNotFoundException.class)
    ProblemDetail handleNotFound(PaymentOrderNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    ProblemDetail handleInsufficient(InsufficientBalanceException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    ProblemDetail handleInvalidState(InvalidPaymentStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }
}
