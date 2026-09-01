package com.fintech.banking.application.service;

import com.fintech.banking.application.port.out.BankAccountRepository;
import com.fintech.banking.application.port.out.BankReconciliationRepository;
import com.fintech.banking.domain.BankCloseSeal;
import com.fintech.banking.domain.BankStatementLine;
import com.fintech.banking.domain.SuspenseEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Emite el sello bancario del día, que es la cifra de control que el cierre consume.
 *
 * <p><b>Si no cuadra, no se sella.</b> Sellar «con observaciones» convierte el sello en un trámite:
 * su único valor es que un sello emitido signifique que ese día cuadró. Cuando no cuadra se publica
 * una alerta — y la alerta tiene consumidor, que es lo que la distingue de un log.
 */
@Service
public class BankSealService {

    private static final Logger log = LoggerFactory.getLogger(BankSealService.class);

    private final BankReconciliationRepository repo;
    private final BankAccountRepository cuentas;
    private final ReconciliationAlertPublisher alertas;

    public BankSealService(BankReconciliationRepository repo, BankAccountRepository cuentas,
                           ReconciliationAlertPublisher alertas) {
        this.repo    = repo;
        this.cuentas = cuentas;
        this.alertas = alertas;
    }

    /**
     * @param saldoContable el saldo de la cuenta del mayor al cierre del día. Lo trae contabilidad:
     *                      banking no postea ni deduce pólizas
     * @param saldoDelBanco el saldo que reporta el estado de cuenta
     */
    @Transactional
    public BankCloseSeal sellar(UUID bankAccountId, LocalDate businessDate, String periodType,
                                BigDecimal saldoContable, BigDecimal saldoDelBanco) {
        cuentas.findById(bankAccountId).orElseThrow(
                () -> new NoSuchElementException("Sin cuenta bancaria " + bankAccountId));

        Optional<BankCloseSeal> yaSellado = repo.findSeal(bankAccountId, businessDate, periodType);
        if (yaSellado.isPresent()) {
            // Reemitir un sello borraría la cifra que alguien ya usó para cuadrar.
            log.info("La cuenta {} ya tiene sello {} del {} — idempotente",
                    bankAccountId, periodType, businessDate);
            return yaSellado.get();
        }

        // Las partidas abiertas se acumulan: una de hace tres días sigue explicando la diferencia
        // de hoy. Sumar sólo las del día dejaría el sello sin cuadrar por algo ya declarado.
        List<SuspenseEntry> partidas = repo.findOpenSuspenseUpTo(bankAccountId, businessDate);
        BigDecimal totalPartidas = partidas.stream()
                .map(SuspenseEntry::aportacionAlSaldo)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BankStatementLine> lineas = repo.findLinesByDate(bankAccountId, businessDate);
        int cruzadas = (int) lineas.stream().filter(l -> "MATCHED".equals(l.getMatchStatus())).count();

        BankCloseSeal sello = BankCloseSeal.calcular(bankAccountId, businessDate, periodType,
                saldoContable, saldoDelBanco, totalPartidas, lineas.size(), cruzadas);

        if (!sello.cuadra()) {
            log.error("El sello bancario NO cuadra cuenta={} fecha={} contable={} banco={} "
                            + "partidas={} diferencia={}",
                    bankAccountId, businessDate, saldoContable, saldoDelBanco, totalPartidas,
                    sello.getDifference());
            alertas.publicarDescuadre(bankAccountId, businessDate, saldoContable, saldoDelBanco,
                    totalPartidas, sello.getDifference(), partidas.size());
            // Se persiste igual: la diferencia es el dato, y no guardarla obligaría a recalcularla
            // para saber qué pasó ese día. Lo que no se emite es la afirmación de que cuadró.
        } else {
            log.info("Sello bancario cuenta={} fecha={} CUADRA — {} líneas, {} cruzadas, {} partidas",
                    bankAccountId, businessDate, lineas.size(), cruzadas, partidas.size());
        }

        return repo.saveSeal(sello);
    }
}
