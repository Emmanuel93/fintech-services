package com.fintech.scoring.infrastructure.adapter.in.api;

import com.fintech.scoring.domain.InvalidScoringPolicyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Una política mal armada es un 400 con el motivo, no un 500: quien la escribe en la consola tiene
 * que saber qué corregir.
 */
@RestControllerAdvice
class ScoringExceptionHandler {

    @ExceptionHandler(InvalidScoringPolicyException.class)
    ProblemDetail handleInvalidPolicy(InvalidScoringPolicyException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/SCORING_INVALID_POLICY"));
        pd.setTitle("Política de scoring inválida");
        return pd;
    }
}
