package com.fintech.stp.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Clave de rastreo: el identificador de la orden ante Banxico.
 *
 * <p>Formato: {@code <prefijo><yyyyMMdd><consecutivo a 14 dígitos>} = 24 caracteres con un prefijo
 * de dos letras.
 *
 * <p>El legado usaba el PK autoincremental de la tabla como consecutivo, atando un identificador
 * de negocio regulado al detalle de implementación de una secuencia de Postgres — y con un prefijo
 * constante, imposible de usar con más de una empresa. Aquí el prefijo viene de la empresa y el
 * consecutivo de una secuencia dedicada por {@code (empresa, día)}.
 */
public record TrackingKey(String value) {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
    private static final int SEQUENCE_DIGITS = 14;
    private static final int MAX_PREFIX_LENGTH = 4;

    public TrackingKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("La clave de rastreo no puede estar vacía");
        }
        if (value.length() > 30) {
            throw new IllegalArgumentException("La clave de rastreo excede 30 caracteres: " + value.length());
        }
    }

    public static TrackingKey generate(String companyPrefix, LocalDate businessDate, long sequence) {
        if (companyPrefix == null || companyPrefix.isBlank()) {
            throw new IllegalArgumentException("La empresa no tiene prefijo de clave de rastreo configurado");
        }
        if (companyPrefix.length() > MAX_PREFIX_LENGTH) {
            throw new IllegalArgumentException(
                    "El prefijo de clave de rastreo excede " + MAX_PREFIX_LENGTH + " caracteres: " + companyPrefix);
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("La fecha de operación es obligatoria");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("El consecutivo no puede ser negativo: " + sequence);
        }
        String padded = String.format(Locale.ROOT, "%0" + SEQUENCE_DIGITS + "d", sequence);
        if (padded.length() > SEQUENCE_DIGITS) {
            throw new IllegalArgumentException(
                    "El consecutivo excede " + SEQUENCE_DIGITS + " dígitos: " + sequence);
        }
        return new TrackingKey(companyPrefix + businessDate.format(DATE) + padded);
    }

    @Override
    public String toString() {
        return value;
    }
}
