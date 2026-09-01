package com.fintech.shared.lock;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * La identidad de un candado: una llave de unicidad compuesta.
 *
 * <p>El formato es {@code fintech:lock:<dominio>:<propósito>:<discriminador>} y no es decorativo.
 * Dos ejecuciones que deben excluirse mutuamente tienen que producir <b>exactamente</b> la misma
 * cadena, y dos que no deben excluirse, cadenas distintas. Construirla concatenando a mano en cada
 * sitio de uso es la forma clásica de que un espacio de más convierta un candado en dos.
 *
 * <p>Ejemplos reales:
 * <pre>
 *   fintech:lock:closing:run:2026-08-24:ACCRUAL:ALL
 *   fintech:lock:closing:unit:9f1c…:3a7d…
 *   fintech:lock:accounting:period-close:202608
 * </pre>
 */
public record LockKey(String domain, String purpose, String discriminator) {

    public static final String PREFIX = "fintech:lock";

    public LockKey {
        domain        = require(domain, "domain");
        purpose       = require(purpose, "purpose");
        discriminator = discriminator == null ? "" : discriminator.trim();
    }

    /**
     * @param parts el discriminador, por segmentos. Se unen con {@code :} en el orden recibido.
     *              Un segmento nulo o en blanco se omite: es lo que permite escribir la misma
     *              llamada para un alcance con y sin sub-alcance sin generar {@code ::}.
     */
    public static LockKey of(String domain, String purpose, Object... parts) {
        String discriminator = parts == null ? "" : Arrays.stream(parts)
                .filter(p -> p != null && !p.toString().isBlank())
                .map(Object::toString)
                .collect(Collectors.joining(":"));
        return new LockKey(domain, purpose, discriminator);
    }

    /** La cadena que se manda a Redis. */
    public String value() {
        return discriminator.isEmpty()
                ? PREFIX + ":" + domain + ":" + purpose
                : PREFIX + ":" + domain + ":" + purpose + ":" + discriminator;
    }

    /**
     * La llave del contador de fencing, derivada de la del candado.
     *
     * <p>Vive en un espacio de nombres distinto ({@code fintech:fence}) porque <b>sobrevive al
     * candado</b>: el contador tiene que seguir creciendo entre adquisiciones, y si compartiera
     * llave con el candado, el {@code DEL} de la liberación lo reiniciaría y dos titulares
     * sucesivos recibirían el mismo número.
     */
    public String fenceKey() {
        return "fintech:fence:" + domain + ":" + purpose
                + (discriminator.isEmpty() ? "" : ":" + discriminator);
    }

    @Override
    public String toString() {
        return value();
    }

    private static String require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("LockKey." + field + " no puede ser nulo ni vacío");
        }
        return v.trim();
    }
}
