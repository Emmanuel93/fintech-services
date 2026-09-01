package com.fintech.creditportfolio.infrastructure.adapter.in.api;

import com.fintech.creditportfolio.domain.CreditAccountNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import com.fintech.creditportfolio.application.port.in.DeferDispositionUseCase;
import com.fintech.creditportfolio.application.port.in.SkipPaymentUseCase;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
class CreditPortfolioExceptionHandler {

    @ExceptionHandler(CreditAccountNotFoundException.class)
    ProblemDetail handleNotFound(CreditAccountNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/CREDIT_PORTFOLIO_NOT_FOUND"));
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/CREDIT_PORTFOLIO_INVALID_STATE"));
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/CREDIT_PORTFOLIO_BAD_REQUEST"));
        return pd;
    }

    /** Fuera de ventana, plazo inválido, producto que no difiere: el motivo va en el cuerpo. */
    @ExceptionHandler(DeferDispositionUseCase.NoSePuedeDiferirException.class)
    ProblemDetail noSePuedeDiferir(DeferDispositionUseCase.NoSePuedeDiferirException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Producto que no admite saltos, tope agotado, cuota ya pagada o de otra cuenta. */
    @ExceptionHandler(SkipPaymentUseCase.NoSePuedeSaltarException.class)
    ProblemDetail noSePuedeSaltar(SkipPaymentUseCase.NoSePuedeSaltarException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
