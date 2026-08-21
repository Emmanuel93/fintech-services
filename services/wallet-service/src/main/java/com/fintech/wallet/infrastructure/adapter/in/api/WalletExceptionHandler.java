package com.fintech.wallet.infrastructure.adapter.in.api;

import com.fintech.wallet.domain.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class WalletExceptionHandler {

    @ExceptionHandler(WalletViewNotFoundException.class)
    ProblemDetail handleNotFound(WalletViewNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(PaymentInstructionNotFoundException.class)
    ProblemDetail handleInstructionNotFound(PaymentInstructionNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(InsufficientCreditException.class)
    ProblemDetail handleInsufficientCredit(InsufficientCreditException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(InsufficientWalletBalanceException.class)
    ProblemDetail handleInsufficientWalletBalance(InsufficientWalletBalanceException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(DispositionBlockedException.class)
    ProblemDetail handleDispositionBlocked(DispositionBlockedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(DuplicatePendingInstructionException.class)
    ProblemDetail handleDuplicate(DuplicatePendingInstructionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/WALLET_INVALID_STATE"));
        return pd;
    }
}
