package com.fintech.disbursement.application;

import com.fintech.disbursement.application.port.out.CompanyMappingRepository;
import com.fintech.disbursement.application.service.CompanyResolutionService;
import com.fintech.disbursement.domain.CompanyMapping;
import com.fintech.disbursement.domain.DisbursementSource;
import com.fintech.disbursement.domain.Rail;
import com.fintech.disbursement.domain.UnresolvedCompanyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * De qué empresa sale el dinero (DB-07).
 *
 * <p>Nada probaba esta clase, y por eso sobrevivió el defecto que costó el carril entero: la guarda
 * de «sin clave» rechazaba <b>antes</b> de mirar el comodín, de modo que toda disposición del
 * sistema murió en la DLT con {@code UNRESOLVED_COMPANY} mientras el comodín estaba sembrado,
 * habilitado y esperando. Cuatro disposiciones autorizadas, cero órdenes de pago, y ni una línea de
 * log en el servicio — la DLT se lo tragó con el offset confirmado.
 *
 * <p>Lo que estas pruebas fijan es la frontera exacta: <b>el comodín no es adivinar</b> —alguien lo
 * sembró diciendo «para este sistema origen, ésta es la empresa»— pero <b>sin comodín no se
 * inventa nada</b>, porque firmar con la llave equivocada saca dinero de la cuenta equivocada.
 */
class ResolucionDeEmpresaTest {

    private static final UUID EMPRESA_DEL_COMODIN = UUID.randomUUID();
    private static final UUID EMPRESA_DE_LA_SUCURSAL = UUID.randomUUID();

    @Test
    @DisplayName("El companyId que ya trae el emisor gana sobre todo lo demás")
    void el_company_id_explicito_manda() {
        UUID explicita = UUID.randomUUID();
        var servicio = new CompanyResolutionService(repositorio(
                mapeo("credit-portfolio", "*", EMPRESA_DEL_COMODIN)));

        assertThat(servicio.resolve(peticion(explicita, "SUC-001"))).isEqualTo(explicita);
    }

    @Test
    @DisplayName("Un mapeo específico gana sobre el comodín")
    void el_mapeo_especifico_gana() {
        var servicio = new CompanyResolutionService(repositorio(
                mapeo("credit-portfolio", "*", EMPRESA_DEL_COMODIN),
                mapeo("credit-portfolio", "SUC-001", EMPRESA_DE_LA_SUCURSAL)));

        assertThat(servicio.resolve(peticion(null, "SUC-001"))).isEqualTo(EMPRESA_DE_LA_SUCURSAL);
    }

    @Test
    @DisplayName("Una clave sin mapeo propio cae al comodín")
    void una_clave_sin_mapeo_cae_al_comodin() {
        var servicio = new CompanyResolutionService(repositorio(
                mapeo("credit-portfolio", "*", EMPRESA_DEL_COMODIN)));

        assertThat(servicio.resolve(peticion(null, "SUC-NUEVA"))).isEqualTo(EMPRESA_DEL_COMODIN);
    }

    // ── La regresión que costó el carril ─────────────────────────────────────

    @Test
    @DisplayName("SIN clave también cae al comodín: no traer clave es la forma más pura de no tener mapeo propio")
    void sin_clave_cae_al_comodin() {
        var servicio = new CompanyResolutionService(repositorio(
                mapeo("credit-portfolio", "*", EMPRESA_DEL_COMODIN)));

        assertThat(servicio.resolve(peticion(null, null))).isEqualTo(EMPRESA_DEL_COMODIN);
        assertThat(servicio.resolve(peticion(null, "   "))).isEqualTo(EMPRESA_DEL_COMODIN);
    }

    @Test
    @DisplayName("Sin clave y sin comodín NO se inventa la empresa")
    void sin_clave_y_sin_comodin_no_se_inventa() {
        var servicio = new CompanyResolutionService(repositorio());

        assertThatThrownBy(() -> servicio.resolve(peticion(null, null)))
                .isInstanceOf(UnresolvedCompanyException.class)
                .hasMessageContaining("no hay comodín");
    }

    @Test
    @DisplayName("Un comodín deshabilitado no resuelve nada — apagarlo tiene que apagarlo de verdad")
    void el_comodin_deshabilitado_no_resuelve() {
        CompanyMapping apagado = mapeo("credit-portfolio", "*", EMPRESA_DEL_COMODIN);
        apagado.disable();
        var servicio = new CompanyResolutionService(repositorio(apagado));

        assertThatThrownBy(() -> servicio.resolve(peticion(null, null)))
                .isInstanceOf(UnresolvedCompanyException.class);
    }

    @Test
    @DisplayName("El comodín es por sistema origen: el de wallet no cubre a cartera")
    void el_comodin_no_cruza_sistemas() {
        var servicio = new CompanyResolutionService(repositorio(
                mapeo("wallet", "*", EMPRESA_DEL_COMODIN)));

        assertThatThrownBy(() -> servicio.resolve(peticion(null, null)))
                .isInstanceOf(UnresolvedCompanyException.class);
    }

    // ── Utilidades ───────────────────────────────────────────────────────────

    private static RequestDisbursementCommand peticion(UUID companyId, String sourceCompanyKey) {
        return new RequestDisbursementCommand(
                "credit-portfolio", DisbursementSource.DISPOSITION, "ref", "evt-1",
                sourceCompanyKey, companyId, null,
                "Diego Zamora Padilla", "002180114414524862", "40", "ZAPD850507TK6", null,
                new BigDecimal("3200.00"), "MXN", "DISPOSICION DE CREDITO", null,
                Rail.SPEI, "corr-1");
    }

    private static CompanyMapping mapeo(String sistema, String clave, UUID empresa) {
        return CompanyMapping.of(sistema, clave, empresa);
    }

    private static CompanyMappingRepository repositorio(CompanyMapping... filas) {
        List<CompanyMapping> datos = new ArrayList<>(List.of(filas));
        return new CompanyMappingRepository() {
            @Override public CompanyMapping save(CompanyMapping m) { datos.add(m); return m; }
            @Override public Optional<CompanyMapping> find(String sistema, String clave) {
                return datos.stream()
                        .filter(m -> m.getSourceSystem().equals(sistema) && m.getSourceKey().equals(clave))
                        .findFirst();
            }
            @Override public List<CompanyMapping> findAll() { return List.copyOf(datos); }
        };
    }
}
