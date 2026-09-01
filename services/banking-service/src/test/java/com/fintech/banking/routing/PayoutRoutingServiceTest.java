package com.fintech.banking.routing;

import com.fintech.banking.application.port.in.ResolvePayoutRouteUseCase.Peticion;
import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.port.out.PayoutRouteRepository;
import com.fintech.banking.application.service.PayoutRoutingService;
import com.fintech.banking.domain.BankAccount;
import com.fintech.banking.domain.Clabe;
import com.fintech.banking.domain.NoPayoutRouteException;
import com.fintech.banking.domain.PayoutDecision;
import com.fintech.banking.domain.PayoutProvider;
import com.fintech.banking.domain.PayoutRail;
import com.fintech.banking.domain.PayoutRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El ruteo es <b>determinista</b>: mismas rutas y misma petición dan siempre la misma cuenta.
 *
 * <p>Sin dobles de framework. Los dos repositorios son listas en memoria: lo que se prueba es la
 * regla de desempate, y un mock verificando llamadas no diría nada de eso.
 */
class PayoutRoutingServiceTest {

    private final Map<UUID, BankAccount> cuentas = new HashMap<>();
    private final List<PayoutRoute> rutas = new ArrayList<>();
    private PayoutRoutingService servicio;

    private static int sufijo = 0;

    private BankAccount cuenta(String nombre) {
        String base = String.format("012180001234%05d", sufijo++);
        BankAccount c = BankAccount.alta(null, "012", nombre,
                Clabe.of(base + Clabe.digitoVerificador(base)), "FINTECH SA DE CV",
                "FIN200101ABC", "MXN", "1101", "2109", "1109", "CL-" + nombre);
        cuentas.put(c.getId(), c);
        return c;
    }

    @BeforeEach
    void init() {
        PayoutRouteRepository repoRutas = new PayoutRouteRepository() {
            @Override public PayoutRoute save(PayoutRoute r) { rutas.add(r); return r; }
            @Override public Optional<PayoutRoute> findById(UUID id) {
                return rutas.stream().filter(r -> r.getId().equals(id)).findFirst();
            }
            @Override public List<PayoutRoute> findAllEnabled() {
                return rutas.stream().filter(PayoutRoute::isEnabled).toList();
            }
            @Override public List<PayoutRoute> findAll() { return List.copyOf(rutas); }
        };
        BankAccountRepository repoCuentas = new BankAccountRepository() {
            @Override public BankAccount save(BankAccount c) { cuentas.put(c.getId(), c); return c; }
            @Override public Optional<BankAccount> findById(UUID id) {
                return Optional.ofNullable(cuentas.get(id));
            }
            @Override public Optional<BankAccount> findByClabe(String clabe) {
                return cuentas.values().stream().filter(c -> c.getClabe().equals(clabe)).findFirst();
            }
            @Override public List<BankAccount> findByCompany(UUID companyId) { return List.of(); }
            @Override public List<BankAccount> findAll() { return List.copyOf(cuentas.values()); }
        };
        servicio = new PayoutRoutingService(repoRutas, repoCuentas);
        rutas.clear();
        cuentas.clear();
    }

    private void ruta(UUID empresa, BankAccount cuenta, int prioridad, String min, String max) {
        rutas.add(PayoutRoute.of(empresa, PayoutRail.SPEI, PayoutProvider.STP, cuenta.getId(),
                new BigDecimal(min), max == null ? null : new BigDecimal(max), prioridad));
    }

    private PayoutDecision resolver(UUID empresa, String monto) {
        return servicio.resolver(new Peticion(empresa, PayoutRail.SPEI, new BigDecimal(monto)));
    }

    @Test
    @DisplayName("la respuesta trae cuenta, rail y proveedor — la decisión completa, no media")
    void decisionCompleta() {
        BankAccount c = cuenta("BBVA");
        ruta(null, c, 100, "0", null);

        PayoutDecision d = resolver(UUID.randomUUID(), "5000");

        assertThat(d.bankAccountId()).isEqualTo(c.getId());
        assertThat(d.rail()).isEqualTo(PayoutRail.SPEI);
        assertThat(d.provider()).isEqualTo(PayoutProvider.STP);
        // El conector necesita esto para armar la cadena original; si sólo recibiera un id tendría
        // que resolverlo contra su propia copia del catálogo, que es de donde venimos.
        assertThat(d.orderingClabe()).isEqualTo(c.getClabe());
        assertThat(d.orderingHolderName()).isEqualTo("FINTECH SA DE CV");
        assertThat(d.providerClientRef()).isEqualTo("CL-BBVA");
    }

    @Test
    @DisplayName("gana la de menor prioridad numérica")
    void ganaLaDeMenorPrioridad() {
        BankAccount principal = cuenta("BBVA");
        BankAccount respaldo  = cuenta("SANTANDER");
        ruta(null, respaldo, 200, "0", null);
        ruta(null, principal, 50, "0", null);

        assertThat(resolver(UUID.randomUUID(), "5000").bankAccountId()).isEqualTo(principal.getId());
    }

    @Test
    @DisplayName("a igual prioridad, la regla de la empresa manda sobre la genérica")
    void laEspecificaGana() {
        UUID empresa = UUID.randomUUID();
        BankAccount generica   = cuenta("BBVA");
        BankAccount deLaEmpresa = cuenta("BANORTE");
        ruta(null, generica, 100, "0", null);
        ruta(empresa, deLaEmpresa, 100, "0", null);

        assertThat(resolver(empresa, "5000").bankAccountId()).isEqualTo(deLaEmpresa.getId());
        // Y otra empresa sigue cayendo en la genérica.
        assertThat(resolver(UUID.randomUUID(), "5000").bankAccountId()).isEqualTo(generica.getId());
    }

    @Test
    @DisplayName("el monto elige la banda")
    void reparteroPorMonto() {
        BankAccount chicos  = cuenta("BBVA");
        BankAccount grandes = cuenta("BANORTE");
        ruta(null, chicos, 100, "0", "10000");
        ruta(null, grandes, 100, "10000.01", null);

        assertThat(resolver(UUID.randomUUID(), "9999").bankAccountId()).isEqualTo(chicos.getId());
        assertThat(resolver(UUID.randomUUID(), "10000").bankAccountId()).isEqualTo(chicos.getId());
        assertThat(resolver(UUID.randomUUID(), "10000.01").bankAccountId()).isEqualTo(grandes.getId());
    }

    @Test
    @DisplayName("una cuenta SUSPENDIDA se salta y gana la siguiente en orden")
    void laSuspendidaSeSalta() {
        // Si la ruta ganara igual, suspender una cuenta no significaría nada: el pago fallaría en el
        // proveedor, que es donde ya no se puede corregir.
        BankAccount caida    = cuenta("BBVA");
        BankAccount respaldo = cuenta("SANTANDER");
        ruta(null, caida, 50, "0", null);
        ruta(null, respaldo, 100, "0", null);

        caida.suspender();

        assertThat(resolver(UUID.randomUUID(), "5000").bankAccountId()).isEqualTo(respaldo.getId());
    }

    @Test
    @DisplayName("sin ruta aplicable el pago NO sale — no hay cuenta de respaldo")
    void sinRutaNoSale() {
        BankAccount c = cuenta("BBVA");
        ruta(null, c, 100, "0", "1000");

        // Caer a un default es exactamente el defecto que este servicio corrige.
        assertThatThrownBy(() -> resolver(UUID.randomUUID(), "5000"))
                .isInstanceOf(NoPayoutRouteException.class)
                .hasMessageContaining("Sin ruta de pago");
    }

    @Test
    @DisplayName("si TODAS las cuentas candidatas están suspendidas, tampoco sale")
    void todasCaidas() {
        BankAccount a = cuenta("BBVA");
        BankAccount b = cuenta("SANTANDER");
        ruta(null, a, 50, "0", null);
        ruta(null, b, 100, "0", null);
        a.suspender();
        b.suspender();

        assertThatThrownBy(() -> resolver(UUID.randomUUID(), "5000"))
                .isInstanceOf(NoPayoutRouteException.class);
    }

    @Test
    @DisplayName("una ruta deshabilitada no participa")
    void deshabilitadaNoParticipa() {
        BankAccount c = cuenta("BBVA");
        ruta(null, c, 100, "0", null);
        rutas.get(0).deshabilitar();

        assertThatThrownBy(() -> resolver(UUID.randomUUID(), "5000"))
                .isInstanceOf(NoPayoutRouteException.class);
    }

    @Test
    @DisplayName("una ruta que apunta a una cuenta inexistente falla RUIDOSAMENTE")
    void rutaHuerfana() {
        // El silencio aquí sería peor: el pago saldría por la siguiente ruta y nadie sabría que la
        // configuración quedó rota.
        rutas.add(PayoutRoute.of(null, PayoutRail.SPEI, PayoutProvider.STP, UUID.randomUUID(),
                BigDecimal.ZERO, null, 100));

        assertThatThrownBy(() -> resolver(UUID.randomUUID(), "5000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apunta a una cuenta que no existe");
    }

    @Test
    @DisplayName("con empate exacto gana la MÁS ANTIGUA, venga como venga de la base")
    void empateExacto() {
        // `findAllEnabled()` no garantiza orden. Sin un desempate final, dos rutas de igual
        // prioridad y especificidad harían que el pago saliera un día por una cuenta y al siguiente
        // por otra sin que nadie cambiara nada. Aquí se barajan las filas a propósito.
        ruta(null, cuenta("BBVA"), 100, "0", null);
        ruta(null, cuenta("BANORTE"), 100, "0", null);

        // La ganadora se calcula aquí con la regla declarada, no se asume: así la prueba comprueba
        // que el servicio ordena de verdad y no que devuelve el primer elemento de la lista.
        UUID esperada = rutas.stream()
                .min(Comparator.comparing(PayoutRoute::getCreatedAt).thenComparing(PayoutRoute::getId))
                .orElseThrow()
                .getBankAccountId();

        UUID empresa = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            java.util.Collections.shuffle(rutas);
            assertThat(resolver(empresa, "5000").bankAccountId()).isEqualTo(esperada);
        }
    }

    @Test
    @DisplayName("una ruta SIN cuenta no se puede ni construir")
    void rutaSinCuenta() {
        assertThatThrownBy(() -> PayoutRoute.of(null, PayoutRail.SPEI, PayoutProvider.STP, null,
                BigDecimal.ZERO, null, 100))
                .hasMessageContaining("sin cuenta ordenante");
    }

    @Test
    @DisplayName("la decisión enmascara la CLABE al imprimirse")
    void noSeImprimeLaClabe() {
        BankAccount c = cuenta("BBVA");
        ruta(null, c, 100, "0", null);

        PayoutDecision d = resolver(UUID.randomUUID(), "5000");
        assertThat(d.toString()).doesNotContain(c.getClabe()).contains("****");
    }
}
