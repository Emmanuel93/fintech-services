package com.fintech.stp.infrastructure.adapter.in.api;

import com.fintech.stp.domain.CompanyNotFoundException;
import com.fintech.stp.domain.InvalidBeneficiaryAccountException;
import com.fintech.stp.domain.InvalidStpOrderStateException;
import com.fintech.stp.domain.OrderingAccountNotFoundException;
import com.fintech.stp.domain.SigningKeyNotAvailableException;
import com.fintech.stp.domain.StpPaymentOrderNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class StpExceptionHandler {

    @ExceptionHandler(StpPaymentOrderNotFoundException.class)
    ProblemDetail handleOrderNotFound(StpPaymentOrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(CompanyNotFoundException.class)
    ProblemDetail handleCompanyNotFound(CompanyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(OrderingAccountNotFoundException.class)
    ProblemDetail handleOrderingAccountNotFound(OrderingAccountNotFoundException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidBeneficiaryAccountException.class)
    ProblemDetail handleInvalidAccount(InvalidBeneficiaryAccountException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidStpOrderStateException.class)
    ProblemDetail handleInvalidState(InvalidStpOrderStateException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    /** 423: la empresa existe, pero operativamente no puede firmar hasta que se rote la llave. */
    @ExceptionHandler(SigningKeyNotAvailableException.class)
    ProblemDetail handleKeyUnavailable(SigningKeyNotAvailableException ex) {
        return problem(HttpStatus.LOCKED, ex.getMessage(), ex.getErrorCode());
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
