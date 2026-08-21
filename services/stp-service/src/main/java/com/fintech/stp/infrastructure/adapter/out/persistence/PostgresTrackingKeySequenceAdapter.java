package com.fintech.stp.infrastructure.adapter.out.persistence;

import com.fintech.stp.application.port.out.TrackingKeySequencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Secuencia de clave de rastreo por empresa y día.
 *
 * <p>Se resuelve con un {@code INSERT … ON CONFLICT DO UPDATE … RETURNING}, que es atómico y crea
 * la fila del día en el mismo viaje. No se usa una {@code SEQUENCE} de Postgres porque hay que
 * reiniciar por día y el valor tiene que ser transaccional con la orden.
 */
@Component
public class PostgresTrackingKeySequenceAdapter implements TrackingKeySequencePort {

    private static final String NEXT_VALUE = """
            INSERT INTO stp.tracking_key_sequences (company_id, business_date, last_value)
            VALUES (?, ?, 1)
            ON CONFLICT (company_id, business_date)
            DO UPDATE SET last_value = stp.tracking_key_sequences.last_value + 1
            RETURNING last_value
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgresTrackingKeySequenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(UUID companyId, LocalDate businessDate) {
        Long value = jdbcTemplate.queryForObject(NEXT_VALUE, Long.class, companyId, Date.valueOf(businessDate));
        if (value == null) {
            throw new IllegalStateException(
                    "No se pudo obtener el consecutivo de clave de rastreo para " + companyId + "/" + businessDate);
        }
        return value;
    }
}
