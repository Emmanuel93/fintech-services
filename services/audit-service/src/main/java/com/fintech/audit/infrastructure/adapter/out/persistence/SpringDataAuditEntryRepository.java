package com.fintech.audit.infrastructure.adapter.out.persistence;

import com.fintech.audit.domain.AuditEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Todas las consultas de listado reciben {@link Pageable} y ninguna puede devolver la tabla
 * entera.
 *
 * <p>audit-service es suscriptor global de Kafka: su tabla es la que más crece de la plataforma y
 * nunca se depura, porque es un log regulatorio. Una consulta sin cota aquí no es lenta, es una
 * bomba de tiempo — funciona en desarrollo con mil filas y revienta en producción con millones,
 * del lado del cliente (buffer) o del servidor (memoria).
 *
 * <p>Todas ordenan por {@code createdAt DESC}, así que el límite devuelve <b>lo más reciente</b>,
 * que es lo que un auditor quiere ver primero.
 */
interface SpringDataAuditEntryRepository extends JpaRepository<AuditEntry, UUID> {

    @Query("SELECT e FROM AuditEntry e WHERE e.partyId = :partyId AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByPartyId(@Param("partyId") String partyId,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    List<AuditEntry> findByAggregateIdOrderByCreatedAtDesc(String aggregateId, Pageable pageable);

    @Query("SELECT e FROM AuditEntry e WHERE e.eventType = :eventType AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByEventType(@Param("eventType") String eventType,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                   Pageable pageable);

    @Query("SELECT e FROM AuditEntry e WHERE e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByDateRange(@Param("from") Instant from, @Param("to") Instant to,
                                     Pageable pageable);

    @Query("SELECT e FROM AuditEntry e WHERE e.actor = :actor AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByActor(@Param("actor") String actor,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                   Pageable pageable);

    @Query("SELECT e FROM AuditEntry e WHERE e.actorIp = :actorIp AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByActorIp(@Param("actorIp") String actorIp,
                                   @Param("from") Instant from,
                                   @Param("to") Instant to,
                                   Pageable pageable);

    @Query("SELECT e FROM AuditEntry e WHERE e.action = :action AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByAction(@Param("action") String action,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                   Pageable pageable);

    @Query("SELECT e FROM AuditEntry e WHERE e.category = :category AND e.createdAt BETWEEN :from AND :to ORDER BY e.createdAt DESC")
    List<AuditEntry> findByCategory(@Param("category") String category,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                   Pageable pageable);
}
