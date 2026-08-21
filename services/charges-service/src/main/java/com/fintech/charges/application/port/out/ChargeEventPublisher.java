package com.fintech.charges.application.port.out;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public interface ChargeEventPublisher {
    default void publishChargeApplied(String eventId, UUID creditAccountId, String chargeType,
                                      BigDecimal totalAmount) {
        publishChargeApplied(eventId, creditAccountId, chargeType, totalAmount, null);
    }

    /**
     * @param effectiveDate el día al que pertenece el cargo; {@code null} = hoy.
     *
     * <p>Viaja hasta contabilidad, que deriva de él el <b>período</b> de la póliza. Sin este dato, un
     * devengo del 30 de junio procesado hoy se asienta en el período de hoy y la contabilidad no
     * puede tener historia.
     */
    void publishChargeApplied(String eventId, UUID creditAccountId, String chargeType,
                              BigDecimal totalAmount, LocalDate effectiveDate);
    void publishChargeReversed(String eventId, UUID creditAccountId, String originalChargeType,
                               BigDecimal amount, boolean waived);
}
