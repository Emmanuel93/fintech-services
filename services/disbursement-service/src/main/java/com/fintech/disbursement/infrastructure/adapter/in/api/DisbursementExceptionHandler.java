package com.fintech.disbursement.infrastructure.adapter.in.api;

import com.fintech.disbursement.domain.DisbursementNotFoundException;
import com.fintech.disbursement.domain.InvalidBeneficiaryAccountException;
import com.fintech.disbursement.domain.InvalidDisbursementInstructionException;
import com.fintech.disbursement.domain.InvalidDisbursementStateException;
import com.fintech.disbursement.domain.NoRoutingRuleException;
import com.fintech.disbursement.domain.UnresolvedCompanyException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class DisbursementExceptionHandler {

    @ExceptionHandler(DisbursementNotFoundException.class)
    ProblemDetail handleNotFound(DisbursementNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(NoRoutingRuleException.class)
    ProblemDetail handleNoRouting(NoRoutingRuleException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(UnresolvedCompanyException.class)
    ProblemDetail handleUnresolvedCompany(UnresolvedCompanyException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidBeneficiaryAccountException.class)
    ProblemDetail handleInvalidAccount(InvalidBeneficiaryAccountException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidDisbursementInstructionException.class)
    ProblemDetail handleInvalidInstruction(InvalidDisbursementInstructionException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    /**
     * Un rail o proveedor desconocido, o un monto no positivo, es un error del que llama — no del
     * servidor. Sin esto sale un 500 en endpoints que administran rutas de dinero.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), "DISBURSEMENT_INVALID_ARGUMENT");
    }

    @ExceptionHandler(InvalidDisbursementStateException.class)
    ProblemDetail handleInvalidState(InvalidDisbursementStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), ex.getErrorCode());
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
