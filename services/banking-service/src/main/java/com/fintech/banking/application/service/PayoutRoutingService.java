package com.fintech.banking.application.service;

import com.fintech.banking.application.port.in.ResolvePayoutRouteUseCase;
import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.port.out.PayoutRouteRepository;
import com.fintech.banking.domain.BankAccount;
import com.fintech.banking.domain.NoPayoutRouteException;
import com.fintech.banking.domain.PayoutDecision;
import com.fintech.banking.domain.PayoutRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Decide <b>de qué cuenta nuestra</b> sale un pago, por qué rail y con qué proveedor.
 *
 * <p>Antes esta decisión estaba partida en dos y ninguna mitad la tomaba entera:
 * {@code disbursement.routing_rules} elegía el <b>proveedor</b> por (empresa, rail, monto), y la
 * <b>cuenta</b> la resolvía el conector de STP con un {@code is_default} por empresa — ciego al
 * saldo, al costo y al horario.
 *
 * <p>El desempate es el mismo que ya usaba el ruteo de proveedor, para que migrar no cambie por
 * dónde sale el dinero hoy: gana la de menor {@code priority}; a igual prioridad, la específica de
 * empresa sobre la genérica.
 *
 * <p><b>Sin ruta aplicable el pago no sale.</b> No hay cuenta de respaldo y es deliberado: caer a un
 * default es exactamente el defecto que este servicio existe para corregir.
 */
@Service
public class PayoutRoutingService implements ResolvePayoutRouteUseCase {

    private static final Logger log = LoggerFactory.getLogger(PayoutRoutingService.class);

    private final PayoutRouteRepository rutas;
    private final BankAccountRepository cuentas;

    public PayoutRoutingService(PayoutRouteRepository rutas, BankAccountRepository cuentas) {
        this.rutas   = rutas;
        this.cuentas = cuentas;
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutDecision resolver(Peticion p) {
        List<PayoutRoute> candidatas = rutas.findAllEnabled().stream()
                .filter(r -> r.cubre(p.companyId(), p.rail(), p.amount()))
                .sorted(Comparator.comparingInt(PayoutRoute::getPriority)
                        .thenComparingInt(PayoutRoute::especificidad)
                        // Los dos desempates finales existen porque `findAllEnabled()` NO garantiza
                        // orden: con dos rutas de igual prioridad y especificidad, la base puede
                        // devolverlas en cualquier orden y el pago saldría un día por una cuenta y
                        // al siguiente por otra, sin que nadie cambiara nada. Gana la más antigua —
                        // la que ya venía operando — y el id cierra el caso imposible del empate
                        // de milisegundo.
                        .thenComparing(PayoutRoute::getCreatedAt)
                        .thenComparing(PayoutRoute::getId))
                .toList();

        // Se recorren en orden y gana la primera cuya cuenta puede operar. Descartar la cuenta
        // suspendida aquí y no antes es lo que hace que suspender una cuenta SIGNIFIQUE algo: si la
        // ruta ganara igual, el pago fallaría en el proveedor, que es donde ya no se puede corregir.
        for (PayoutRoute ruta : candidatas) {
            BankAccount cuenta = cuentas.findById(ruta.getBankAccountId()).orElseThrow(
                    () -> new IllegalStateException("La ruta " + ruta.getId()
                            + " apunta a una cuenta que no existe: " + ruta.getBankAccountId()));

            if (!cuenta.puedeOperar()) {
                log.warn("Ruta {} descartada: la cuenta {} está {}",
                        ruta.getId(), cuenta.getId(), cuenta.getStatus());
                continue;
            }

            PayoutDecision decision = PayoutDecision.de(ruta, cuenta);
            log.info("Ruta resuelta {}", decision);   // toString() enmascara la CLABE
            return decision;
        }

        // El hueco de configuración se grita, no se rellena.
        log.error("Sin ruta de pago aplicable empresa={} rail={} monto={} — {} candidatas descartadas",
                p.companyId(), p.rail(), p.amount(), candidatas.size());
        throw new NoPayoutRouteException(p.companyId(), p.rail(), p.amount());
    }
}
