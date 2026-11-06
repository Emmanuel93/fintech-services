package com.fintech.statement.application.service;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.port.out.StatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Vuelve a intentar lo que falló —cartera caída, un pod que murió a medio documento— con los mismos
 * datos del corte, que quedaron guardados al registrarlo.
 */
@Service
public class StatementRetryService {

    private static final Logger log = LoggerFactory.getLogger(StatementRetryService.class);

    private static final int BATCH = 200;

    private final StatementRepository statements;
    private final StatementGenerationService generation;
    private final StatementProperties properties;

    public StatementRetryService(StatementRepository statements, StatementGenerationService generation,
                                 StatementProperties properties) {
        this.statements = statements;
        this.generation = generation;
        this.properties = properties;
    }

    public record RetryResult(int attempted, int generated, int failed) {}

    public RetryResult retry() {
        int intentados = 0, generados = 0, fallidos = 0;
        for (UUID id : statements.retryable(properties.getMaxAttempts(), properties.getStaleAfter(), BATCH)) {
            intentados++;
            switch (generation.generate(id)) {
                case GENERATED -> generados++;
                case FAILED -> fallidos++;
                case ALREADY_TAKEN -> { }
            }
        }
        if (intentados > 0) {
            log.info("Reintento de estados de cuenta: {} intentados, {} generados, {} siguen fallando",
                    intentados, generados, fallidos);
        }
        return new RetryResult(intentados, generados, fallidos);
    }
}
