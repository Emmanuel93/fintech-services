package com.fintech.banking;

import com.fintech.banking.application.port.in.ResolvePayoutRouteUseCase;
import com.fintech.banking.application.port.in.ResolvePayoutRouteUseCase.Peticion;
import com.fintech.banking.domain.Clabe;
import com.fintech.banking.domain.PayoutDecision;
import com.fintech.banking.domain.PayoutProvider;
import com.fintech.banking.domain.PayoutRail;
import com.fintech.shared.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El carril del dinero <b>existe</b> desde el arranque.
 *
 * <p>Esta prueba nace de un hallazgo, no de un requisito. Buscando qué migrar de
 * {@code disbursement.routing_rules} resultó que <b>nadie la sembró nunca</b>: ni un changeset ni
 * un script. Con la tabla vacía, cada orden se aplazaba con {@code NO_ROUTING_RULE} y a los seis
 * intentos moría en {@code FAILED}; del lado del conector, registrar una orden lanzaba
 * {@code OrderingAccountNotFoundException}. Nadie lo notó porque el {@code Noop} de cartera
 * cortocircuitaba el carril antes de llegar ahí.
 *
 * <p>Mover la configuración sin sembrarla habría reproducido el hueco con otro nombre. Esto lo fija.
 */
@SpringBootTest
class SeedDelCarrilIT extends AbstractIntegrationTest {

    @Override protected List<String> esquemas() { return List.of("banking"); }
    @Override protected List<String> preservar() { return List.of("bank_accounts", "payout_routes"); }

    @Autowired ResolvePayoutRouteUseCase tesoreria;

    @Test
    @DisplayName("un ambiente recién levantado YA puede decir por dónde sale un pago")
    void elCarrilExiste() {
        PayoutDecision d = tesoreria.resolver(
                new Peticion(UUID.randomUUID(), PayoutRail.SPEI, new BigDecimal("5000.00")));

        assertThat(d.provider()).isEqualTo(PayoutProvider.STP);
        assertThat(d.rail()).isEqualTo(PayoutRail.SPEI);
        // Y la cuenta que responde es utilizable de verdad: CLABE válida y referencia de proveedor.
        assertThat(Clabe.of(d.orderingClabe()).valor()).isEqualTo(d.orderingClabe());
        assertThat(d.orderingHolderName()).isNotBlank();
        assertThat(d.providerClientRef()).isNotBlank();
    }

    @Test
    @DisplayName("la ruta sembrada cede ante cualquier regla real")
    void elSeedNoEstorba() {
        // Prioridad 900: en producción tesorería da de alta sus cuentas y cualquier regla suya gana.
        Integer prioridad = jdbcTemplate.queryForObject("""
                SELECT priority FROM banking.payout_routes
                 WHERE payout_route_id = '9b1d0000-0000-4000-8000-0000000000a1'
                """, Integer.class);

        assertThat(prioridad).isEqualTo(900);
    }

    @Test
    @DisplayName("la cuenta sembrada declara sus tres cuentas del mayor")
    void lasTresCuentas() {
        var fila = jdbcTemplate.queryForMap("""
                SELECT ledger_account, suspense_credit_account, suspense_debit_account
                  FROM banking.bank_accounts
                 WHERE bank_account_id = '9b1d0000-0000-4000-8000-000000000001'
                """);

        assertThat(fila.get("ledger_account")).isEqualTo("1101");
        assertThat(fila.get("suspense_credit_account")).isEqualTo("2109");
        assertThat(fila.get("suspense_debit_account")).isEqualTo("1109");
    }
}
