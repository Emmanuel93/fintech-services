package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import com.fintech.creditportfolio.domain.CreditAccount;
import com.fintech.creditportfolio.domain.CreditAccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataCreditAccountRepository extends JpaRepository<CreditAccount, UUID> {
    Optional<CreditAccount> findByContractId(UUID contractId);
    List<CreditAccount> findByObligorPartyId(UUID obligorPartyId);
    List<CreditAccount> findByStatus(CreditAccountStatus status);
    List<CreditAccount> findByCreditAccountIdIn(Collection<UUID> ids);

    /**
     * Listado del backoffice. Los filtros nulos no filtran: una sola consulta
     * cubre todas las combinaciones sin armar SQL a mano ni multiplicar
     * métodos, y la paginación la resuelve la base.
     *
     * <p>{@code q} llega ya en minúsculas y con comodines. No se le aplica
     * {@code LOWER()} aquí por dos razones: Postgres liga un parámetro nulo sin
     * tipo como {@code bytea} y {@code lower(bytea)} no existe —la consulta
     * reventaba con el filtro vacío—, y comparar contra la columna en
     * minúsculas es lo que permite usar el índice funcional.
     *
     * <p>{@code partyIds} nulo se ignora (mismo criterio); con valor filtra a los
     * obligados de esa página — es lo que deja hidratar una tabla sin N+1.
     */
    @Query("SELECT a FROM CreditAccount a "
         + "WHERE (:status IS NULL OR a.status = :status) "
         + "  AND (:productType IS NULL OR a.productType = :productType) "
         + "  AND (:minDpd IS NULL OR a.daysDelinquent >= :minDpd) "
         + "  AND (:maxDpd IS NULL OR a.daysDelinquent <= :maxDpd) "
         + "  AND (:partyIds IS NULL OR a.obligorPartyId IN :partyIds) "
         + "  AND (:q IS NULL OR LOWER(a.contractNumber) LIKE :q)")
    Page<CreditAccount> search(@Param("status") CreditAccountStatus status,
                                @Param("productType") String productType,
                                @Param("q") String q,
                                @Param("minDpd") Integer minDpd,
                                @Param("maxDpd") Integer maxDpd,
                                @Param("partyIds") Collection<UUID> partyIds,
                                Pageable pageable);

    // ── Distribución por dimensión (para /stats). Agregado en la base. ─────────

    @Query(value = "SELECT status AS key, COUNT(*) AS cnt, COALESCE(SUM(principal_balance), 0) AS principal "
                 + "  FROM credit_portfolio.credit_accounts "
                 + " GROUP BY status ORDER BY principal DESC", nativeQuery = true)
    List<Object[]> statsByStatus();

    @Query(value = "SELECT product_type AS key, COUNT(*) AS cnt, COALESCE(SUM(principal_balance), 0) AS principal "
                 + "  FROM credit_portfolio.credit_accounts "
                 + " GROUP BY product_type ORDER BY principal DESC", nativeQuery = true)
    List<Object[]> statsByProductType();

    /**
     * Buckets de días de atraso alineados a la escala operativa de cobranza.
     * Los límites son fijos por ahora; hacerlos configurables desde backoffice
     * es el follow-up de Task 4.
     */
    @Query(value = "SELECT CASE WHEN days_delinquent <= 0  THEN 'CURRENT' "
                 + "            WHEN days_delinquent <= 30 THEN '1-30' "
                 + "            WHEN days_delinquent <= 60 THEN '31-60' "
                 + "            WHEN days_delinquent <= 90 THEN '61-90' "
                 + "            ELSE '90+' END AS key, "
                 + "       COUNT(*) AS cnt, COALESCE(SUM(principal_balance), 0) AS principal "
                 + "  FROM credit_portfolio.credit_accounts "
                 + " GROUP BY 1 ORDER BY MIN(days_delinquent)", nativeQuery = true)
    List<Object[]> statsByDpdBucket();

    /**
     * Agregados de cartera en una sola pasada por la base.
     *
     * <p>Los tramos son los de IFRS-9 por días de atraso: al corriente hasta 30,
     * incremento significativo de riesgo hasta 90, y deteriorado de ahí en
     * adelante. Se calcula aquí y no en Java para no leer la cartera entera en
     * cada carga del tablero — que es la primera pantalla de cada usuario.
     */
    @Query(value = "SELECT COUNT(*) AS active_accounts, "
                 + "       COUNT(DISTINCT obligor_party_id) AS active_obligors, "
                 + "       COALESCE(SUM(principal_balance), 0) AS principal_balance, "
                 + "       COALESCE(SUM(principal_balance + accrued_interest_balance + penalty_balance), 0) AS total_debt, "
                 + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent <= 30), 0) AS stage1, "
                 + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent BETWEEN 31 AND 90), 0) AS stage2, "
                 + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent > 90), 0) AS stage3, "
                 + "       COUNT(*) FILTER (WHERE days_delinquent > 0) AS delinquent_accounts "
                 + "  FROM credit_portfolio.credit_accounts "
                 + " WHERE status = 'ACTIVE'", nativeQuery = true)
    Object[] summaryRow();

    /**
     * Mezcla de cartera por producto, agregada en la base.
     *
     * <p>El tablero la pinta como distribución; sin agrupar en SQL habría que
     * leer todas las cuentas activas para contarlas por tipo.
     */
    @Query(value = "SELECT product_type, product_behavior, COUNT(*) AS accounts, "
                 + "       COALESCE(SUM(principal_balance), 0) AS capital, "
                 + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent > 30), 0) AS vencida "
                 + "  FROM credit_portfolio.credit_accounts "
                 + " WHERE status = 'ACTIVE' "
                 + " GROUP BY product_type, product_behavior "
                 + " ORDER BY capital DESC", nativeQuery = true)
    List<Object[]> productMixRows();

    /**
     * Cartera por <b>unidad de origen</b>, agregada en la base.
     *
     * <p>Un {@code GROUP BY origin_unit_code} con la misma mecánica de {@code FILTER} que
     * {@code summaryRow()}: una pasada, sin leer la cartera en memoria. El índice parcial
     * {@code idx_credit_accounts_origin_unit} cubre el filtro.
     *
     * <p>Van <b>dos consultas</b> —con filtro y sin él— en vez de un
     * {@code (:codes IS NULL OR …)}. Ese truco funciona en JPQL, pero en SQL <b>nativo</b>
     * Postgres no puede inferir el tipo de una colección nula y contesta
     * «could not determine data type of parameter $1». Es la misma familia del problema conocido
     * con parámetros temporales, y la salida limpia es no pasar NULL: si no hay filtro, la
     * consulta no lo lleva.
     *
     * <p>Las cuentas sin sellar se agrupan bajo {@code (sin unidad)} en vez de repartirse: un
     * hueco visible se puede corregir, uno repartido con una heurística no se puede ni detectar.
     */
    @Query(value = ORIGIN_UNIT_SELECT
                 + "   AND origin_unit_code IN (:codes) "
                 + ORIGIN_UNIT_TAIL, nativeQuery = true)
    List<Object[]> statsByOriginUnitRows(@Param("codes") Collection<String> codes);

    /** La misma agregación sin acotar a unidades. */
    @Query(value = ORIGIN_UNIT_SELECT + ORIGIN_UNIT_TAIL, nativeQuery = true)
    List<Object[]> statsByOriginUnitRows();

    String ORIGIN_UNIT_SELECT =
              "SELECT COALESCE(origin_unit_code, '(sin unidad)') AS unit_code, "
            + "       COUNT(*) AS accounts, "
            + "       COUNT(*) FILTER (WHERE days_delinquent > 0) AS delinquent_accounts, "
            + "       COALESCE(SUM(principal_balance), 0) AS principal, "
            + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent <= 30), 0) AS stage1, "
            + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent BETWEEN 31 AND 90), 0) AS stage2, "
            + "       COALESCE(SUM(principal_balance) FILTER (WHERE days_delinquent > 90), 0) AS stage3 "
            + "  FROM credit_portfolio.credit_accounts "
            + " WHERE status = 'ACTIVE' ";

    String ORIGIN_UNIT_TAIL = " GROUP BY 1 ORDER BY principal DESC";
}
