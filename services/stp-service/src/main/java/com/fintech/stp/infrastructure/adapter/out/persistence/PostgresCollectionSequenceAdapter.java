package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.CollectionSequencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** The consecutive of a company's CLABE range: one atomic upsert, transactional with the account it numbers. */
@Component
public class PostgresCollectionSequenceAdapter implements CollectionSequencePort {

    private static final String NEXT_VALUE = """
            INSERT INTO stp.collection_sequences (company_id, last_value) VALUES (?, 1)
            ON CONFLICT (company_id) DO UPDATE SET last_value = stp.collection_sequences.last_value + 1
            RETURNING last_value
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresCollectionSequenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(UUID companyId) {
        Long value = jdbcTemplate.queryForObject(NEXT_VALUE, Long.class, companyId);
        if (value == null) {
            throw new IllegalStateException("No se pudo obtener el consecutivo de CLABE de la empresa " + companyId);
        }
        return value;
    }
}
