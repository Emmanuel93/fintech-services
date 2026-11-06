package com.fintech.creditportfolio.application.port.out;

import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditAccountRepository {
    CreditAccount save(CreditAccount account);

    /** Loans opened before payment references existed: they are given one at startup. */
    List<CreditAccount> findWithoutPaymentReference();
    Optional<CreditAccount> findById(UUID creditAccountId);

    /**
     * Takes the account's row lock for the current transaction, so concurrent balance changes on the same
     * account are applied one after the other. No-op outside a transaction.
     */
    void lockForUpdate(UUID creditAccountId);

    /**
     * Locks the account row and returns its current state. The lock alone is not enough when the same
     * transaction already read the account before (the overpayment check does): JPA would hand back the
     * copy it holds, read before the lock, and the mutation would run on stale balances. This refreshes
     * after the lock is granted, so what gets mutated is what the previous writer committed.
     */
    Optional<CreditAccount> lockAndLoad(UUID creditAccountId);
    Optional<CreditAccount> findByContractId(UUID contractId);
    List<CreditAccount> findByObligorPartyId(UUID obligorPartyId);
    List<CreditAccount> findAllByStatus(CreditAccountStatus status);

    /**
     * Búsqueda paginada para el backoffice.
     *
     * <p>Todos los filtros son opcionales y se resuelven en SQL. Nada de traer
     * la cartera completa y filtrar en memoria: el listado se pagina desde la
     * base porque el backoffice lo abre a diario y la tabla sólo crece.
     *
     * @param q         texto libre contra número de contrato (case-insensitive)
     * @param partyIds  hidrata una tabla de otro servicio sin N+1: pásale los ids
     *                  de obligado de la página y filtra a esas cuentas. Null/vacío = sin filtro.
     */
    Page<CreditAccount> search(CreditAccountStatus status,
                                String productType,
                                String q,
                                Integer minDaysDelinquent,
                                Integer maxDaysDelinquent,
                                Collection<UUID> partyIds,
                                Pageable pageable);

    /**
     * Hidratación por lote: varias cuentas por id en una sola consulta.
     *
     * <p>El endpoint que evita el N+1 cuando otro servicio necesita pintar
     * datos de la cuenta por fila. Null/vacío devuelve vacío.
     */
    List<CreditAccount> findByCreditAccountIdIn(Collection<UUID> ids);

    /** Agregados del tablero, calculados en la base. */
    PortfolioSummary summary();

    /** Mezcla por producto para el tablero, agrupada en la base. */
    List<ProductMix> productMix();

    /**
     * Distribución de la cartera agrupada por una dimensión.
     *
     * @param groupBy {@code status} | {@code productType} | {@code dpdBucket}
     */
    List<PortfolioStat> stats(String groupBy);

    /**
     * Cartera por unidad de origen (la que colocó el crédito, sellada al activarlo).
     *
     * @param unitCodes códigos a incluir; vacío o nulo = todas las unidades
     */
    List<OriginUnitStat> statsByOriginUnit(java.util.Collection<String> unitCodes);
}
