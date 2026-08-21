package com.fintech.origination.infrastructure.adapter.out.persistence;

import com.fintech.origination.application.port.out.ProspectRepository;
import com.fintech.origination.domain.Prospect;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaProspectAdapter implements ProspectRepository {

    private final SpringDataProspectRepository jpa;

    JpaProspectAdapter(SpringDataProspectRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Prospect save(Prospect prospect) {
        return jpa.save(prospect);
    }

    @Override
    public Optional<Prospect> findById(UUID prospectId) {
        return jpa.findById(prospectId);
    }

    @Override
    public boolean existsByCurp(String curp) {
        return jpa.existsByCurp(curp);
    }

    @Override
    public boolean existsByPhone(String phone) {
        return jpa.existsByPhone(phone);
    }

    @Override
    public List<Prospect> findByContact(String email, String phone, String curp) {
        // Se pregunta sólo por lo que se recibió, y se unen los resultados por id: el
        // mismo prospecto puede casar por dos criterios y no debe aparecer dos veces.
        Map<UUID, Prospect> encontrados = new LinkedHashMap<>();
        if (hasText(email)) jpa.findByEmailIgnoreCase(email.trim()).forEach(p -> encontrados.put(p.getProspectId(), p));
        if (hasText(phone)) jpa.findByPhone(phone.trim()).forEach(p -> encontrados.put(p.getProspectId(), p));
        if (hasText(curp))  jpa.findByCurpIgnoreCase(curp.trim()).forEach(p -> encontrados.put(p.getProspectId(), p));
        return List.copyOf(encontrados.values());
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
