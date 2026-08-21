package com.fintech.notifications.infrastructure.adapter.in.api;

import com.fintech.notifications.domain.InvalidRecipientException;
import com.fintech.notifications.domain.MissingNotificationPolicyException;
import com.fintech.notifications.domain.UnknownRecipientException;
import com.fintech.notifications.domain.NotificationPolicyNotFoundException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@Order(1)
class NotificationExceptionHandler {

    @ExceptionHandler(NotificationPolicyNotFoundException.class)
    ProblemDetail handleNotFound(NotificationPolicyNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(MissingNotificationPolicyException.class)
    ProblemDetail handleMissingPolicy(MissingNotificationPolicyException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(UnknownRecipientException.class)
    ProblemDetail handleUnknownRecipient(UnknownRecipientException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), ex.getErrorCode());
    }

    @ExceptionHandler(InvalidRecipientException.class)
    ProblemDetail handleInvalidRecipient(InvalidRecipientException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), ex.getErrorCode());
    }

    private ProblemDetail problem(HttpStatus status, String message, String errorCode) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://fintech.com/errors/" + errorCode));
        return pd;
    }
}
