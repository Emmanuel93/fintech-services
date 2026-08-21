package com.fintech.salesorg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * sales-org-service — la estructura comercial interna.
 *
 * <p>Modela la jerarquía de la red propia (hoy nacional → región → zona → sucursal) con
 * <b>niveles configurables</b>: se pueden insertar posiciones intermedias sin migrar código. Es la
 * base de la matriz de escalado operativo: quién reporta a quién, y por tanto qué alcance tiene cada
 * empleado sobre la cartera y las solicitudes.
 */
@SpringBootApplication
public class SalesOrgApplication {
    public static void main(String[] args) {
        SpringApplication.run(SalesOrgApplication.class, args);
    }
}
