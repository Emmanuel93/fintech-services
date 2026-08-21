package com.fintech.creditproduct.infrastructure.adapter.in.api;

import com.fintech.creditproduct.domain.CreditProductNotFoundException;
import com.fintech.creditproduct.domain.InvalidProductTransitionException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(1)
public class CreditProductExceptionHandler {

    @ExceptionHandler(CreditProductNotFoundException.class)
    public ProblemDetail handleNotFound(CreditProductNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Product not found");
        return pd;
    }

    @ExceptionHandler(InvalidProductTransitionException.class)
    public ProblemDetail handleInvalidTransition(InvalidProductTransitionException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Invalid product state transition");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArg(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setTitle("Validation error");
        return pd;
    }
}
