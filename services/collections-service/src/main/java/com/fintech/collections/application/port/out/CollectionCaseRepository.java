package com.fintech.collections.application.port.out;

import com.fintech.collections.domain.CaseStatus;
import com.fintech.collections.domain.CollectionCase;
import com.fintech.collections.domain.DelinquencyBucket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionCaseRepository {
    Optional<CollectionCase> findById(UUID caseId);
    /** CC-01: at most one OPEN/MANAGED/LEGAL case per account. */
    Optional<CollectionCase> findActiveByCreditAccountId(UUID creditAccountId);
    /** For WriteOffCandidatesJob — cases in the given statuses with daysDelinquent >= threshold. */
    List<CollectionCase> findByStatusInAndDaysDelinquentGreaterThanEqual(List<CaseStatus> statuses, int daysDelinquent);
    CollectionCase save(CollectionCase collectionCase);

    /**
     * Búsqueda paginada para la bandeja del backoffice.
     *
     * <p>Todos los filtros son opcionales y se resuelven en SQL. Es la consulta con la que un gestor
     * empieza el día —«mis casos de 31 a 60 días»— y la tabla sólo crece, así que paginar en memoria
     * dejaría de funcionar justo cuando la cartera vencida importa.
     *
     * <p>La alternativa que se descartó era listar cuentas morosas desde credit-portfolio y pedirle
     * a cobranza el caso de cada fila: una llamada por renglón para pintar una tabla.
     *
     * @param minDaysDelinquent cota inferior de mora, inclusiva. Null = sin cota.
     */
    Page<CollectionCase> search(CaseStatus status,
                                DelinquencyBucket bucket,
                                String productType,
                                String assignedAgentId,
                                Integer minDaysDelinquent,
                                Pageable pageable);
}
