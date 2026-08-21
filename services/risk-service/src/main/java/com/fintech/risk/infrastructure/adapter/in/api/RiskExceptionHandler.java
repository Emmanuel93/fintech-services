package com.fintech.risk.infrastructure.adapter.in.api;

import com.fintech.risk.domain.InvalidPolicyStateException;
import com.fintech.risk.domain.MissingProvisionPolicyException;
import com.fintech.risk.domain.ProvisionPolicyNotFoundException;
import com.fintech.risk.domain.RiskProfileNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class RiskExceptionHandler {

    @ExceptionHandler(RiskProfileNotFoundException.class)
    ProblemDetail handleProfileNotFound(RiskProfileNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(ProvisionPolicyNotFoundException.class)
    ProblemDetail handlePolicyNotFound(ProvisionPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(MissingProvisionPolicyException.class)
    ProblemDetail handleMissingPolicy(MissingProvisionPolicyException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidPolicyStateException.class)
    ProblemDetail handleInvalidPolicyState(InvalidPolicyStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "RISK_BAD_REQUEST");
    }

    private ProblemDetail problem(HttpStatus status, String message, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        return pd;
    }
}
