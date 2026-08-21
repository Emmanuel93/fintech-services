package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.domain.DuplicateLivePlacementException;
import com.fintech.beneficiary.domain.InsufficientLineException;
import com.fintech.beneficiary.domain.InvalidPlacementTransitionException;
import com.fintech.beneficiary.domain.PlacementAccessDeniedException;
import com.fintech.beneficiary.domain.PlacementNotFoundException;
import com.fintech.beneficiary.domain.PlacementNotReadyException;
import com.fintech.beneficiary.domain.PlacementValidationException;
import com.fintech.shared.exception.DomainException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class BeneficiaryExceptionHandler {

    @ExceptionHandler(PlacementNotFoundException.class)
    ProblemDetail handleNotFound(PlacementNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(PlacementAccessDeniedException.class)
    ProblemDetail handleForbidden(PlacementAccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, ex);
    }

    /**
     * 409 y no 400: el cliente no mandó nada malformado, pidió algo que el estado actual de la
     * colocación no permite. Aprobar una que ya venció es un conflicto, no un error de sintaxis.
     */
    @ExceptionHandler(InvalidPlacementTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidPlacementTransitionException ex) {
        ProblemDetail pd = problem(HttpStatus.CONFLICT, ex);
        pd.setProperty("fromStatus", ex.getFrom().name());
        pd.setProperty("toStatus", ex.getTo().name());
        return pd;
    }

    @ExceptionHandler(DuplicateLivePlacementException.class)
    ProblemDetail handleDuplicate(DuplicateLivePlacementException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(InsufficientLineException.class)
    ProblemDetail handleInsufficientLine(InsufficientLineException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(PlacementNotReadyException.class)
    ProblemDetail handleNotReady(PlacementNotReadyException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(PlacementValidationException.class)
    ProblemDetail handleValidation(PlacementValidationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex);
    }

    private static ProblemDetail problem(HttpStatus status, DomainException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        pd.setProperty("errorCode", ex.getErrorCode());
        return pd;
    }
}
