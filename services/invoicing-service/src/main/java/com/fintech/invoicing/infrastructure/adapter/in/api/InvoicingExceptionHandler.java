package com.fintech.invoicing.infrastructure.adapter.in.api;

import com.fintech.invoicing.domain.InvoiceNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class InvoicingExceptionHandler {
    @ExceptionHandler(InvoiceNotFoundException.class)
    ProblemDetail handleNotFound(InvoiceNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }
}
