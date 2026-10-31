package com.fintech.origination.infrastructure.adapter.in.api;

import com.fintech.origination.domain.CooldownActiveException;
import com.fintech.origination.domain.CreditApplicationNotFoundException;
import com.fintech.origination.domain.DuplicateActiveApplicationException;
import com.fintech.origination.domain.DuplicateProspectException;
import com.fintech.origination.domain.PersonNotVerifiedException;
import com.fintech.origination.domain.PrivacyNoticeRequiredException;
import com.fintech.origination.domain.PromoterCodeNotResolvableException;
import com.fintech.origination.domain.ProspectNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
class OriginationExceptionHandler {

    @ExceptionHandler(DuplicateProspectException.class)
    ProblemDetail handleDuplicateProspect(DuplicateProspectException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(PrivacyNoticeRequiredException.class)
    ProblemDetail handlePrivacyNoticeRequired(PrivacyNoticeRequiredException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler({CreditApplicationNotFoundException.class, ProspectNotFoundException.class})
    ProblemDetail handleNotFound(RuntimeException ex) {
        String code = (ex instanceof com.fintech.shared.exception.DomainException de)
                ? de.getErrorCode() : "NOT_FOUND";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + code));
        return pd;
    }

    @ExceptionHandler(DuplicateActiveApplicationException.class)
    ProblemDetail handleDuplicateActiveApplication(DuplicateActiveApplicationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    @ExceptionHandler(CooldownActiveException.class)
    ProblemDetail handleCooldownActive(CooldownActiveException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    /**
     * La persona todavía no pasó KYC y listas: 422 con el motivo, no un 500.
     *
     * <p>Sin este handler la compuerta funcionaba y se veía rota: la excepción llegaba a la página
     * de error por defecto de Spring como «Internal Server Error», y quien la recibía —el BFF, la
     * app, un analista— no podía distinguir «su identidad sigue en revisión» de «se cayó
     * origination». Es un estado de negocio esperado y se dice como tal; el {@code reason} viaja
     * aparte porque los dos bloqueos se le explican distinto a la persona.
     */
    @ExceptionHandler(PersonNotVerifiedException.class)
    ProblemDetail handlePersonNotVerified(PersonNotVerifiedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/ORIGINATION_PERSON_NOT_VERIFIED"));
        pd.setTitle("La persona aún no está verificada");
        pd.setProperty("prospectId", ex.prospectId());
        pd.setProperty("reason", ex.reason());
        // Los dos estados por separado: a una identidad en revisión se le dice «espera»; a un
        // rechazo, otra cosa. Que la app no tenga que leer el texto del motivo para saberlo.
        pd.setProperty("identityStatus", ex.identityStatus());
        pd.setProperty("screeningStatus", ex.screeningStatus());
        return pd;
    }

    /** promoterCode que no corresponde a ningún distribuidor — se rechaza la solicitud (CM-07). */
    @ExceptionHandler(PromoterCodeNotResolvableException.class)
    ProblemDetail handlePromoterNotResolvable(PromoterCodeNotResolvableException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }

    /** Covers illegal state transitions (e.g. approval on non-pending-review application). */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail handleIllegalState(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/ORIGINATION_INVALID_STATE_TRANSITION"));
        return pd;
    }

    /** Concurrent requests that race past the in-memory duplicate check and hit the DB unique index. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Operation would violate a data integrity constraint");
        pd.setType(URI.create("https://fintech.com/errors/ORIGINATION_CONFLICT"));
        return pd;
    }
}
