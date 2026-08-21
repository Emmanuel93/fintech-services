package com.fintech.origination.infrastructure.adapter.out.persistence;

import com.fintech.origination.domain.Prospect;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataProspectRepository extends JpaRepository<Prospect, UUID> {

    boolean existsByCurp(String curp);

    boolean existsByPhone(String phone);

    // Tres consultas derivadas y no una con tres parámetros opcionales: un parámetro
    // nulo en una comparación JPQL deja a Postgres sin tipo que inferir, y el
    // resultado no es un error sino una consulta que nunca casa —que es peor,
    // porque se ve igual que "no hay nadie con ese dato".
    List<Prospect> findByEmailIgnoreCase(String email);

    List<Prospect> findByPhone(String phone);

    List<Prospect> findByCurpIgnoreCase(String curp);
}
