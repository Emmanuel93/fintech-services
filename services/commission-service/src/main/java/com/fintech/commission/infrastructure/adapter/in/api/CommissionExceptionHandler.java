package com.fintech.commission.infrastructure.adapter.in.api;

import com.fintech.commission.domain.CommissionPolicyNotFoundException;
import com.fintech.commission.domain.InvalidCommissionRecordStateException;
import com.fintech.commission.domain.MissingCommissionPolicyException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class CommissionExceptionHandler {

    @ExceptionHandler(CommissionPolicyNotFoundException.class)
    ProblemDetail handleNotFound(CommissionPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(MissingCommissionPolicyException.class)
    ProblemDetail handleMissingPolicy(MissingCommissionPolicyException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidCommissionRecordStateException.class)
    ProblemDetail handleInvalidState(InvalidCommissionRecordStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    private ProblemDetail problem(HttpStatus status, String message, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        return pd;
    }
}
