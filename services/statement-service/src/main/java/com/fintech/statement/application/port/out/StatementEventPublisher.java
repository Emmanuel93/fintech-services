package com.fintech.statement.application.port.out;

import com.fintech.statement.domain.Statement;

public interface StatementEventPublisher {

    /** {@code statement.statement-generated}: el documento existe y se puede descargar. */
    void publishGenerated(Statement statement);
}
