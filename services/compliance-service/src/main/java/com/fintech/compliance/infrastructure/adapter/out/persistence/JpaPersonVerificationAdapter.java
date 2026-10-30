package com.fintech.compliance.infrastructure.adapter.out.persistence;

import com.fintech.compliance.application.port.out.PersonVerificationRepository;
import com.fintech.compliance.application.port.out.VerificationQueueRepository;
import com.fintech.compliance.domain.IdentityStatus;
import com.fintech.compliance.domain.PersonVerification;
import com.fintech.compliance.domain.ScreeningStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface SpringDataPersonVerificationRepository extends JpaRepository<PersonVerification, UUID> {
    Optional<PersonVerification> findByProspectId(UUID prospectId);
    Optional<PersonVerification> findByPartyId(UUID partyId);

    /**
     * Los filtros llegan siempre llenos —el adaptador expande «vacío» a «todos»— porque un
     * {@code IN} con lista vacía es inválido en JPQL, y la alternativa (un parámetro bandera por
     * filtro) duplica la consulta sin ganar nada.
     */
    @Query("""
            SELECT v FROM PersonVerification v
             WHERE v.identityStatus IN :identity
               AND v.screeningStatus IN :screening
               AND (:text IS NULL
                    OR LOWER(v.subjectFullName) LIKE :text
                    OR LOWER(v.subjectCurp)     LIKE :text)
            """)
    Page<PersonVerification> search(@Param("identity") Set<IdentityStatus> identity,
                                    @Param("screening") Set<ScreeningStatus> screening,
                                    @Param("text") String text,
                                    Pageable pageable);
}

@Component
class JpaPersonVerificationAdapter implements PersonVerificationRepository, VerificationQueueRepository {

    private final SpringDataPersonVerificationRepository repo;

    JpaPersonVerificationAdapter(SpringDataPersonVerificationRepository repo) { this.repo = repo; }

    @Override public Optional<PersonVerification> findByProspectId(UUID p) { return repo.findByProspectId(p); }
    @Override public Optional<PersonVerification> findByPartyId(UUID p)    { return repo.findByPartyId(p); }
    @Override public Optional<PersonVerification> findById(UUID id)        { return repo.findById(id); }
    @Override public PersonVerification save(PersonVerification v)         { return repo.save(v); }

    @Override public java.util.List<PersonVerification> findPage(int page, int size) {
        // Orden estable por fecha de creación: sin él, dos páginas consecutivas pueden repetir y
        // saltarse filas, y un re-cruce dejaría gente sin mirar sin que nada lo indicara.
        return repo.findAll(org.springframework.data.domain.PageRequest.of(page, size,
                org.springframework.data.domain.Sort.by("createdAt").ascending())).getContent();
    }

    @Override
    public Page<PersonVerification> search(Set<IdentityStatus> identity, Set<ScreeningStatus> screening,
                                           String text, Pageable pageable) {
        Set<IdentityStatus> i = identity == null || identity.isEmpty()
                ? EnumSet.allOf(IdentityStatus.class) : identity;
        Set<ScreeningStatus> s = screening == null || screening.isEmpty()
                ? EnumSet.allOf(ScreeningStatus.class) : screening;
        String like = text == null || text.isBlank() ? null : "%" + text.trim().toLowerCase() + "%";
        return repo.search(i, s, like, pageable);
    }
}
