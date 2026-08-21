package com.fintech.audit.infrastructure.adapter.in.api;

import com.fintech.audit.domain.AuditEntryNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
public class AuditExceptionHandler {

    @ExceptionHandler(AuditEntryNotFoundException.class)
    ProblemDetail handleNotFound(AuditEntryNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/AUDIT_ENTRY_NOT_FOUND"));
        return pd;
    }
}
