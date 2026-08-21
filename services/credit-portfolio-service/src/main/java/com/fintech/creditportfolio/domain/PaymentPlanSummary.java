package com.fintech.creditportfolio.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Resumen del plan de pagos de una cuenta, derivado de su calendario.
 *
 * <p>El saldo dice cuánto se debe; esto dice <em>en qué punto del plan va</em>:
 * cuántas mensualidades se cubrieron, cuál toca, de cuánto es y cuándo vence.
 * Es lo que la app necesita para mostrar "Pago 3 de 12" y la fecha del próximo
 * pago sin tener que descargar el calendario completo ni recalcularlo por su
 * cuenta — un cálculo del lado del cliente se desincroniza en cuanto hay un
 * pago parcial, una reestructura o un día de gracia.
 *
 * <p>Todos los campos son opcionales: un producto revolvente no tiene
 * calendario, y una cuenta recién activada puede no tenerlo todavía. En ese
 * caso se devuelve {@link #empty()} y el canal decide cómo presentarlo.
 */
public record PaymentPlanSummary(
        Integer paidInstallments,
        Integer totalInstallments,
        Integer nextInstallmentNumber,
        LocalDate nextDueDate,
        BigDecimal nextInstallmentAmount,
        BigDecimal principalPaid,
        BigDecimal overdueAmount,
        Integer overdueInstallments
) {

    public static PaymentPlanSummary empty() {
        return new PaymentPlanSummary(null, null, null, null, null, null, null, null);
    }

    /**
     * Arma el resumen a partir del calendario.
     *
     * <p>"La que toca" es la primera no pagada por número de mensualidad, no
     * por fecha: si hay una vencida sin cubrir, esa es la que se debe, aunque
     * la siguiente ya esté a la vuelta. Cobrar primero lo más viejo es la regla
     * de aplicación de pagos y la que el cliente espera ver.
     */
    public static PaymentPlanSummary from(List<Installment> schedule) {
        return from(schedule, LocalDate.now());
    }

    /**
     * El plan de una línea revolvente: la unión de los calendarios de todas sus disposiciones.
     *
     * <p>Se ordena por <b>fecha de vencimiento</b> y no por número de mensualidad, que es la
     * diferencia con un amortizable. Cada colocación numera sus cuotas desde 1, así que al juntar
     * tres colocaciones hay tres «cuota 1» y ordenar por número las intercalaría sin sentido: «la
     * que toca» sería la primera de la colocación más vieja aunque venciera dentro de un mes.
     *
     * <p>Por lo mismo el número de mensualidad se deja en nulo: «pago 3 de 12» no significa nada
     * sobre una línea que tiene tres calendarios distintos. Lo que sí significa —cuántas cuotas
     * lleva cubiertas, cuánto debe vencido, qué paga a continuación y cuándo— se conserva.
     */
    public static PaymentPlanSummary fromRevolving(List<Installment> installments) {
        PaymentPlanSummary base = from(installments, LocalDate.now(), Comparator.comparing(
                Installment::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())));
        return new PaymentPlanSummary(
                base.paidInstallments(), base.totalInstallments(),
                null,                                  // el número de cuota no aplica a una línea
                base.nextDueDate(), base.nextInstallmentAmount(),
                base.principalPaid(), base.overdueAmount(), base.overdueInstallments());
    }

    static PaymentPlanSummary from(List<Installment> schedule, LocalDate today) {
        return from(schedule, today, Comparator.comparingInt(Installment::getInstallmentNumber));
    }

    static PaymentPlanSummary from(List<Installment> schedule, LocalDate today,
                                    Comparator<Installment> orden) {
        if (schedule == null || schedule.isEmpty()) return empty();

        List<Installment> ordered = schedule.stream().sorted(orden).toList();

        int paid = (int) ordered.stream()
                .filter(i -> i.getStatus() == InstallmentStatus.PAID)
                .count();

        Installment next = ordered.stream()
                .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                .findFirst()
                .orElse(null);

        BigDecimal principalPaid = ordered.stream()
                .filter(i -> i.getStatus() == InstallmentStatus.PAID)
                .map(Installment::getPrincipalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Vencida es la que pasó su fecha y sigue sin cubrirse, la haya marcado
        // ya el job nocturno o no. Depender sólo del estado deja al cliente
        // viendo "sin adeudo vencido" durante las horas que van entre el
        // vencimiento real y la corrida del job.
        List<Installment> overdue = ordered.stream()
                .filter(i -> i.getStatus() != InstallmentStatus.PAID)
                .filter(i -> i.getStatus() == InstallmentStatus.OVERDUE
                          || (i.getDueDate() != null && i.getDueDate().isBefore(today)))
                .toList();

        BigDecimal overdueAmount = overdue.stream()
                .map(Installment::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new PaymentPlanSummary(
                paid,
                ordered.size(),
                next != null ? next.getInstallmentNumber() : null,
                next != null ? next.getDueDate() : null,
                next != null ? next.getTotalAmount() : null,
                principalPaid,
                overdueAmount,
                overdue.size());
    }
}
