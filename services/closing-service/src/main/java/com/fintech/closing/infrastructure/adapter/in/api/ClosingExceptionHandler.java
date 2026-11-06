package com.fintech.closing.infrastructure.adapter.in.api;

import com.fintech.closing.application.service.cutoff.CutoffRunService.CutoffGateClosed;
import com.fintech.closing.application.service.cutoff.CutoffRunService.CutoffNotDue;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(assignableTypes = CutoffRunController.class)
class ClosingExceptionHandler {

    @ExceptionHandler(CutoffNotDue.class)
    ProblemDetail notDue(CutoffNotDue e) {
        return problem(HttpStatus.CONFLICT, "CLOSING_CUTOFF_NOT_DUE", "El corte todavía no puede correr", e.getMessage());
    }

    @ExceptionHandler(CutoffGateClosed.class)
    ProblemDetail gateClosed(CutoffGateClosed e) {
        return problem(HttpStatus.CONFLICT, "CLOSING_CUTOFF_GATE_CLOSED", "El corte espera al cierre del día", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "CLOSING_CUTOFF_CONFLICT", "El corte no admite la operación", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create(type));
        p.setTitle(title);
        return p;
    }
}
