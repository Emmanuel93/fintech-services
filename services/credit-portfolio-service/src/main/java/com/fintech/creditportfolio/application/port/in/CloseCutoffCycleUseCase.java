package com.fintech.creditportfolio.application.port.in;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * El corte cerró: cartera materializa <b>lo exigible del ciclo</b>.
 *
 * <p>Es la pieza sin la cual una revolvente pura no puede caer en mora nunca. El envejecido busca
 * <b>cuotas vencidas</b>; una compra con tarjeta que nace sin calendario no tiene ninguna, así que
 * sin esto saldría invariablemente con cero días de atraso — y ese cero arrastra a riesgo, a
 * cobranza y al quebranto detrás (AN-20).
 *
 * <p>Por eso BK-24, BK-22 y BK-27 son un solo cambio: quitar el calendario sin dar al corte algo
 * que exigir deja la línea sin nada que vencer.
 */
public interface CloseCutoffCycleUseCase {

    record CorteCerrado(UUID creditAccountId,
                        int cycleNumber,
                        LocalDate cutoffDate,
                        LocalDate paymentDueDate,
                        BigDecimal balanceAtCutoff) {}

    void onCutoffClosed(CorteCerrado corte);
}
