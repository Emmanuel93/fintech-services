package com.fintech.scoring.application.port.out;

import com.fintech.scoring.domain.ScoringPolicy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoringPolicyRepository {
    ScoringPolicy save(ScoringPolicy policy);

    /**
     * Escribe lo pendiente antes de seguir.
     *
     * <p>Hace falta al sustituir una política: sólo puede haber una activa por producto, y el orden
     * natural de escritura de JPA inserta la nueva <b>antes</b> de aplicar el UPDATE que desactiva
     * la anterior. El índice único salta y el alta muere con un error de base filtrado hasta la
     * consola, cuando la operación era perfectamente válida.
     */
    void flush();

    Optional<ScoringPolicy> findById(UUID policyId);

    /**
     * La política vigente de un producto.
     *
     * <p>Se busca <b>sólo</b> por producto desde la migración 013. Antes la llave incluía el tipo de
     * prospecto, y eso hacía que una línea de distribuidora nunca encontrara su política: la
     * política estaba escrita contra {@code DISTRIBUTOR} y el prospecto llega como
     * {@code INDIVIDUAL} o {@code BUSINESS} desde que el distribuidor es un rol (I-03). Como quien
     * no encuentra política omite la evaluación con un warning, la solicitud se quedaba quieta sin
     * que nada lo dijera. El tipo de prospecto no separaba ninguna política de otra — sólo podía
     * fallar.
     */
    Optional<ScoringPolicy> findActiveBy(String productTypeIntent);
    List<ScoringPolicy> findAllActive();
}
