package com.fintech.stp.application.port.in;

public interface PollSettlementsUseCase {

    /**
     * Consulta a STP el desenlace de las órdenes en vuelo y publica los eventos que correspondan.
     *
     * <p>Sustituye a los webhooks del legado: la plataforma no expone nada a internet, así que la
     * información llega porque la vamos a buscar.
     *
     * @return cuántas observaciones nuevas se aplicaron
     */
    int pollInFlightOrders();
}
