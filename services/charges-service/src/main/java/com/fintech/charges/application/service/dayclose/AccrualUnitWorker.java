package com.fintech.charges.application.service.dayclose;

import com.fintech.charges.application.port.out.AccrualUnitStore;
import com.fintech.charges.application.port.out.AccrualUnitStore.AccountTotals;
import com.fintech.charges.application.port.out.AccrualUnitStore.Claimed;
import com.fintech.charges.application.service.InterestAccrualService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El devengo de una cuenta en el cierre de un día: ordinario, moratorio y la marca de la unidad, en una
 * sola transacción. Si el token ya no es el vigente —el monitor dio la cuenta por perdida y otro pod la
 * tomó—, la marca no entra y todo se deshace: la cuenta devenga una vez aunque dos pods la trabajen.
 * Los cargos salen al broker después del commit (ver {@link InterestAccrualService}).
 */
@Service
public class AccrualUnitWorker {

    private final InterestAccrualService accrual;
    private final AccrualUnitStore units;

    public AccrualUnitWorker(InterestAccrualService accrual, AccrualUnitStore units) {
        this.accrual = accrual;
        this.units   = units;
    }

    @Transactional
    public AccountTotals accrue(LocalDate businessDate, UUID creditAccountId, Claimed claimed) {
        // The basis comes from the unit, frozen at planning from the portfolio's snapshot of D: a payment
        // or a disposition after D, or a late retry days later, does not change what D accrues.
        BigDecimal ordinario = accrual.accrueInterestOnBasis(claimed.scheduleId(), businessDate, claimed.basis());
        BigDecimal moratorio = accrual.accrueMoratoriumOnBasis(claimed.scheduleId(), businessDate, claimed.basis());
        AccountTotals totales = new AccountTotals(
                ordinario.signum() > 0 ? 1 : 0, ordinario, moratorio.signum() > 0 ? 1 : 0, moratorio);
        if (!units.complete(businessDate, creditAccountId, claimed.fencingToken(), totales)) {
            throw new StaleUnitException(businessDate, creditAccountId);
        }
        return totales;
    }

    /** Otro pod tiene ya la cuenta con un token más nuevo: este devengo se deshace. */
    public static class StaleUnitException extends RuntimeException {
        public StaleUnitException(LocalDate businessDate, UUID creditAccountId) {
            super("La cuenta " + creditAccountId + " del " + businessDate + " ya la tomó otro pod");
        }
    }
}
