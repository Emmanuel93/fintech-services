package com.fintech.compliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * D16 — Cumplimiento: las listas contra las que se comprueba a una persona.
 *
 * <p>Dos mitades y sólo una construida hoy: la <b>carga</b> de listas de gobierno desde CSV (lo que
 * hay), y la <b>consulta</b> de si alguien coincide (lo que sigue). Ver
 * {@code docs/ANALISIS_KYC_AML_POR_PERSONA.md}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class ComplianceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplianceServiceApplication.class, args);
    }
}
