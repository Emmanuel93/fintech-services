package com.fintech.beneficiary;

import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * beneficiary-service — la colocación B2B2C.
 *
 * <p>Un distribuidor con línea revolvente le presta a sus clientes. Cada cliente hace su propio KYC
 * desde su teléfono, autoriza su propia consulta de buró y recibe el depósito en su cuenta; el
 * distribuidor ve el historial completo —Kredius no filtra por score— y decide, firmando que asume
 * el riesgo.
 *
 * <p>El servicio no inventa dominio: compone el que ya existe. Lo suyo es el hilo que los une —el
 * agregado {@code Placement}, la liga de invitación, la constancia de autorización de buró y la
 * evidencia de la decisión del distribuidor.
 */
@SpringBootApplication
@EnableConfigurationProperties(BeneficiaryProperties.class)
public class BeneficiaryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BeneficiaryServiceApplication.class, args);
    }
}
