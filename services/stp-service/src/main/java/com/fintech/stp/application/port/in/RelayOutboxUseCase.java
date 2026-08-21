package com.fintech.stp.application.port.in;

public interface RelayOutboxUseCase {

    /** Envía a STP lo que quedó pendiente en el outbox. @return cuántos mensajes se procesaron */
    int relayPending();
}
