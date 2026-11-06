package com.fintech.salesorg.infrastructure.adapter.in.api;

import com.fintech.salesorg.domain.CatalogUnavailableException;
import com.fintech.salesorg.domain.DuplicateCodeException;
import com.fintech.salesorg.domain.InvalidHierarchyException;
import com.fintech.salesorg.domain.InvalidPlacementPolicyException;
import com.fintech.salesorg.domain.OrgLevelNotFoundException;
import com.fintech.salesorg.domain.OrgUnitNotFoundException;
import com.fintech.shared.exception.DomainException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
class SalesOrgExceptionHandler {

    @ExceptionHandler({OrgLevelNotFoundException.class, OrgUnitNotFoundException.class})
    ProblemDetail handleNotFound(DomainException ex) {
        return problem(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(DuplicateCodeException.class)
    ProblemDetail handleDuplicate(DuplicateCodeException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler({InvalidHierarchyException.class, InvalidPlacementPolicyException.class})
    ProblemDetail handleInvalidHierarchy(DomainException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex);
    }

    /** The catalog could not be read: the policy is not saved unchecked, and the caller is told to retry. */
    @ExceptionHandler(CatalogUnavailableException.class)
    ProblemDetail handleCatalogUnavailable(CatalogUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ex);
    }

    /** Carreras que pasan la verificación en memoria y chocan con un índice único en la base. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "La operación viola una restricción de integridad");
        pd.setType(URI.create("https://fintech.com/errors/SALES_ORG_CONFLICT"));
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, DomainException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setType(URI.create("https://fintech.com/errors/" + ex.getErrorCode()));
        return pd;
    }
}
