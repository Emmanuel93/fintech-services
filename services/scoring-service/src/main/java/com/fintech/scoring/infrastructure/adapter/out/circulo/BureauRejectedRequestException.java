package com.fintech.scoring.infrastructure.adapter.out.circulo;

/**
 * El buró rechazó la petición (4xx): repetirla da lo mismo. Sólo vive dentro del adaptador, que la
 * convierte en un reporte {@code ERROR} con el código HTTP.
 */
class BureauRejectedRequestException extends RuntimeException {

    private final int status;

    BureauRejectedRequestException(int status) {
        super("Círculo de Crédito rechazó la petición: HTTP " + status);
        this.status = status;
    }

    int status() { return status; }
}
