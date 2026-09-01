package com.fintech.creditproduct.application;

import com.fintech.creditproduct.application.port.out.CreditProductDefinitionRepository;
import com.fintech.creditproduct.application.port.out.EligibilityRuleRepository;
import com.fintech.creditproduct.application.port.out.ProductEventPublisher;
import com.fintech.creditproduct.application.port.out.RateCardRepository;
import com.fintech.creditproduct.application.service.CreditProductCatalogService;
import com.fintech.creditproduct.domain.CreditProductDefinition;
import com.fintech.creditproduct.domain.ProductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Una instalación desde cero tiene que poder originar un crédito.
 *
 * <p>No podía. Los productos se siembran con un {@code INSERT} de Liquibase ya en {@code ACTIVE}, y
 * un {@code INSERT} no emite {@code product-activated}. Sobre base limpia el catálogo mostraba
 * <b>nueve productos activos</b> y cartera tenía <b>cero</b> configuraciones, así que toda alta
 * moría con «Sin configuración del producto». El catálogo se veía sano y el fallo aparecía tres
 * servicios más allá.
 *
 * <p>Reemitir al arrancar es seguro porque el consumidor hace <em>upsert</em> por
 * {@code (código, versión)}, y convierte una clase entera de problema en autorreparable.
 */
@ExtendWith(MockitoExtension.class)
class UnaInstalacionDesdeCeroPropagaElCatalogoTest {

    @Mock CreditProductDefinitionRepository repository;
    @Mock RateCardRepository rateCards;
    @Mock EligibilityRuleRepository eligibilityRules;
    @Mock ProductEventPublisher eventPublisher;

    private CreditProductCatalogService servicio() {
        return new CreditProductCatalogService(repository, rateCards, eligibilityRules, eventPublisher);
    }

    @Test
    @DisplayName("Al arrancar se reemite un evento por cada producto activo")
    void reemite_uno_por_producto_activo() {
        // Los mocks se construyen ANTES del stub externo: `given` dentro de `given` deja el
        // primero sin terminar y Mockito lo reporta en la línea equivocada.
        List<CreditProductDefinition> activos = List.of(producto(), producto(), producto());
        given(repository.findByStatus(ProductStatus.ACTIVE)).willReturn(activos);

        servicio().republicarCatalogoAlArrancar();

        verify(eventPublisher, times(3)).publishProductActivated(any());
    }

    @Test
    @DisplayName("Sin productos activos no se publica nada — no se inventa un catálogo")
    void sin_activos_no_publica() {
        given(repository.findByStatus(ProductStatus.ACTIVE)).willReturn(List.of());

        servicio().republicarCatalogoAlArrancar();

        verify(eventPublisher, never()).publishProductActivated(any());
    }

    @Test
    @DisplayName("Si uno falla, los demás se publican igual")
    void uno_que_falla_no_frena_a_los_demas() {
        List<CreditProductDefinition> activos = List.of(producto(), producto(), producto());
        given(repository.findByStatus(ProductStatus.ACTIVE)).willReturn(activos);
        willThrow(new RuntimeException("kafka caído"))
                .willDoNothing()
                .given(eventPublisher).publishProductActivated(any());

        // Un catálogo a medias es peor que uno con un hueco conocido: el hueco se ve en el log.
        servicio().republicarCatalogoAlArrancar();

        verify(eventPublisher, times(3)).publishProductActivated(any());
    }

    private static CreditProductDefinition producto() {
        CreditProductDefinition d = org.mockito.Mockito.mock(CreditProductDefinition.class);
        given(d.getProductType()).willReturn(com.fintech.creditproduct.domain.ProductType.PERSONAL_LOAN);
        given(d.getBehavior()).willReturn(com.fintech.creditproduct.domain.ProductBehavior.INSTALLMENT);
        given(d.getTargetAudience()).willReturn(com.fintech.creditproduct.domain.TargetAudience.B2C);
        return d;
    }
}
