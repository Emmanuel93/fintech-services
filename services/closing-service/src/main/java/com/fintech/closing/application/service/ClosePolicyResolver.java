package com.fintech.closing.application.service;

import com.fintech.closing.application.port.out.ClosePolicyRepository;
import com.fintech.closing.domain.ClosePolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resuelve qué política aplica a una cuenta, y la <b>congela</b>.
 *
 * <p>Precedencia: producto concreto &gt; tipo de producto &gt; global. La regla vive en el dominio
 * ({@link ClosePolicy#specificity()}) y no en un {@code ORDER BY}, porque es una decisión de negocio
 * y tiene que poder probarse sin base de datos.
 *
 * <p><b>Por qué se congela en la unidad de trabajo.</b> Una corrida re-ejecutada del día 15 tiene
 * que usar la política vigente <em>el día 15</em>. Sin el congelado, un cambio de política hoy
 * reescribiría el pasado en la siguiente re-corrida y el número publicado dejaría de reproducirse.
 * Es el mismo principio que ya rige el período contable del hecho.
 */
@Service
public class ClosePolicyResolver {

    private final ClosePolicyRepository policies;

    public ClosePolicyResolver(ClosePolicyRepository policies) {
        this.policies = policies;
    }

    /**
     * @param businessDate la fecha del cierre. Se descartan las políticas que aún no estaban
     *                     vigentes ese día — no la de hoy.
     */
    @Transactional(readOnly = true)
    public Optional<ClosePolicy> resolve(String productId, String productType, LocalDate businessDate) {
        List<ClosePolicy> candidatas = policies.findActiveForScopes(
                productId == null ? List.of() : List.of(productId),
                productType == null ? List.of() : List.of(productType));

        return candidatas.stream()
                .filter(p -> p.vigenteEn(businessDate))
                .max(Comparator.comparingInt(ClosePolicy::specificity)
                        .thenComparing(ClosePolicy::getEffectiveDate));
    }

    /**
     * La política, o un fallo explícito.
     *
     * <p>No hay política por defecto en código: inventarle una a un producto que no la declaró es
     * cómo se acaba devengando con una convención que nadie eligió. Si falta, falta la global.
     */
    @Transactional(readOnly = true)
    public ClosePolicy require(String productId, String productType, LocalDate businessDate) {
        return resolve(productId, productType, businessDate).orElseThrow(() ->
                new IllegalStateException("Sin política de cierre para producto=" + productType
                        + " (id=" + productId + ") vigente el " + businessDate
                        + ". Debe existir al menos la política GLOBAL."));
    }
}
