package com.fintech.creditportfolio.infrastructure.adapter.out.persistence;

import java.util.List;
import com.fintech.creditportfolio.domain.BalanceEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

interface SpringDataBalanceEventRepository extends JpaRepository<BalanceEvent, UUID> {
    boolean existsBySourceEventId(String sourceEventId);
    long countByCreditAccountId(UUID creditAccountId);

    java.util.Optional<BalanceEvent> findBySourceEventId(String sourceEventId);

    java.util.Optional<BalanceEvent> findFirstByCreditAccountIdAndAppliedAtLessThanOrderByAppliedAtDesc(
            UUID creditAccountId, java.time.Instant appliedAt);

    java.util.List<BalanceEvent> findByCreditAccountIdAndValueDateBetweenOrderByValueDateAscAppliedAtAsc(
            UUID creditAccountId, java.time.LocalDate from, java.time.LocalDate to);

    java.util.Optional<BalanceEvent> findFirstByCreditAccountIdAndValueDateLessThanOrderByValueDateDescAppliedAtDesc(
            UUID creditAccountId, java.time.LocalDate before);

    @Query("SELECT coalesce(sum(e.deltaAmount), 0) FROM BalanceEvent e WHERE e.creditAccountId = :id "
         + "AND e.eventType LIKE 'CHARGE%' AND e.valueDate BETWEEN :from AND :to")
    BigDecimal chargesBetween(@Param("id") UUID id, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("SELECT coalesce(-sum(e.deltaAmount), 0) FROM BalanceEvent e WHERE e.creditAccountId = :id "
         + "AND e.eventType IN ('PAYMENT_APPLIED', 'PAYMENT_RETURNED', 'COMMISSION_NETTED', 'COMMISSION_NETTED_REVERSED') "
         + "AND e.valueDate <= :to")
    BigDecimal netPaymentsUpTo(@Param("id") UUID id, @Param("to") LocalDate to);

    // A distributor's netted commission covers a statement as her cash does, and so does a quita: they travel with the
    // payments (StatementCoverage.paymentsFrom).
    @Query("SELECT e FROM BalanceEvent e WHERE e.creditAccountId = :id "
         + "AND e.eventType IN ('PAYMENT_APPLIED', 'PAYMENT_RETURNED', 'COMMISSION_NETTED', 'COMMISSION_NETTED_REVERSED', "
         + "'AGREEMENT_QUITA_PARCIAL') "
         + "ORDER BY e.valueDate, e.appliedAt")
    List<BalanceEvent> paymentsOf(@Param("id") UUID id);
}
