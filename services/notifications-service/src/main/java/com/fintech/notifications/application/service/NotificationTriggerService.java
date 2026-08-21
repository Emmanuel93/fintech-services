package com.fintech.notifications.application.service;

import com.fintech.notifications.application.port.out.CreditAccountProgressRepository;
import com.fintech.notifications.application.port.out.NotificationPreferenceRepository;
import com.fintech.notifications.domain.CreditAccountProgress;
import com.fintech.notifications.domain.EventType;
import com.fintech.notifications.domain.NotificationPreference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Punto de entrada único para los 7 listeners Kafka (ver infrastructure/adapter/in/messaging) —
 * traduce cada evento de dominio a una llamada de correlación (ContactResolutionService) y/o de
 * envío (NotificationDispatchService). NT-12: ningún método de aquí es invocado por un
 * {@code @Scheduled} — todos son reacciones directas a un mensaje Kafka.
 */
@Service
public class NotificationTriggerService {

    private static final Logger log = LoggerFactory.getLogger(NotificationTriggerService.class);

    private final ContactResolutionService contactResolutionService;
    private final NotificationDispatchService dispatchService;
    private final NotificationPreferenceRepository preferenceRepository;
    private final CreditAccountProgressRepository progressRepository;

    public NotificationTriggerService(ContactResolutionService contactResolutionService,
                                       NotificationDispatchService dispatchService,
                                       NotificationPreferenceRepository preferenceRepository,
                                       CreditAccountProgressRepository progressRepository) {
        this.contactResolutionService = contactResolutionService;
        this.dispatchService = dispatchService;
        this.preferenceRepository = preferenceRepository;
        this.progressRepository = progressRepository;
    }

    /** Paso 1 de correlación — {@code origination.prospect-created}. No dispara notificación. */
    public void onProspectCreated(UUID prospectId, String firstName, String phone, String email) {
        contactResolutionService.onProspectCreated(prospectId, firstName, phone, email);
    }

    /** #1 Ofertas de crédito — {@code origination.offer-presented}. También arma el paso 2 de correlación. */
    public void onOfferPresented(String sourceEventId, UUID applicationId, UUID prospectId,
                                  BigDecimal offeredAmount, Integer offeredTerm, BigDecimal nominalRate,
                                  BigDecimal cat, Instant validUntil) {
        contactResolutionService.onOfferPresented(applicationId, prospectId, offeredTerm);
        ContactInfo contact = contactResolutionService.resolveProspectContact(prospectId);

        Map<String, String> vars = baseVars(contact);
        vars.put("offeredAmount", money(offeredAmount));
        vars.put("offeredTerm", String.valueOf(offeredTerm));
        vars.put("nominalRate", percent(nominalRate));
        vars.put("cat", percent(cat));
        vars.put("validUntil", validUntil == null ? "" : validUntil.toString());
        vars.put("link", "");

        // Sin Party todavía en este punto del ciclo de vida — el recipient es el prospectId.
        dispatchService.dispatch(sourceEventId, EventType.OFFER_PRESENTED, prospectId, contact, null, vars);
    }

    /** #2 Bienvenida — {@code credit-portfolio.credit-account-activated}. También resuelve el paso 3. */
    public void onCreditAccountActivated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                          UUID applicationId, String productType, BigDecimal creditLimit,
                                          BigDecimal nominalRate) {
        ContactInfo contact = contactResolutionService
                .onCreditAccountActivated(creditAccountId, obligorPartyId, applicationId, productType)
                .orElse(ContactInfo.empty());
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("productType", productType);
        vars.put("creditLimit", money(creditLimit));
        vars.put("nominalRate", percent(nominalRate));
        vars.put("link", "");

        dispatchService.dispatch(sourceEventId, EventType.WELCOME_ACTIVATED, obligorPartyId, contact, preference, vars);
    }

    /** #3 Desembolso — {@code credit-portfolio.disposition-completed}. */
    public void onDispositionCompleted(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                        BigDecimal amount) {
        ContactInfo contact = contactResolutionService.resolvePartyContact(obligorPartyId);
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("amount", money(amount));

        dispatchService.dispatch(sourceEventId, EventType.DISBURSEMENT_COMPLETED, obligorPartyId, contact, preference, vars);
    }

    /** #4 Recordatorio de pago — {@code collections.pre-due-reminder-triggered}. Actualiza la cuota vigente. */
    public void onPreDueReminderTriggered(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                           LocalDate dueDate, BigDecimal installmentAmount) {
        CreditAccountProgress progress = progressRepository.findById(creditAccountId)
                .orElseGet(() -> CreditAccountProgress.init(creditAccountId, obligorPartyId, null, null));
        progress.updateCurrentInstallment(dueDate, installmentAmount);
        progressRepository.save(progress);

        ContactInfo contact = contactResolutionService.resolvePartyContact(obligorPartyId);
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("installmentAmount", money(installmentAmount));
        vars.put("dueDate", dueDate == null ? "" : dueDate.toString());
        vars.put("link", "");

        dispatchService.dispatch(sourceEventId, EventType.PAYMENT_REMINDER, obligorPartyId, contact, preference, vars);
    }

    /**
     * La mensualidad llegó a su fecha y sigue sin cubrirse —
     * {@code credit-portfolio.installment-due}.
     *
     * <p>Un mismo evento cubre dos mensajes distintos según el día: si vence
     * hoy es un recordatorio, y si la fecha ya pasó es un aviso de mora. Se
     * distingue aquí y no en el emisor porque el job puede correr con retraso
     * o reprocesarse, y lo que decide el tono es cuántos días lleva vencida —
     * no cuándo se publicó el evento.
     *
     * <p>El obligado sale del progreso de la cuenta: el evento habla de la
     * mensualidad, no de la persona. Sin progreso cargado no hay a quién
     * avisarle, así que no se dispara nada en vez de fallar.
     */
    public void onInstallmentDue(String sourceEventId, UUID creditAccountId,
                                 Integer installmentNumber, LocalDate dueDate,
                                 BigDecimal installmentAmount) {
        Optional<CreditAccountProgress> progressOpt = progressRepository.findById(creditAccountId);
        if (progressOpt.isEmpty()) {
            log.debug("installment-due sin progreso de cuenta creditAccountId={} — sin aviso",
                    creditAccountId);
            return;
        }
        CreditAccountProgress progress = progressOpt.get();
        progress.updateCurrentInstallment(dueDate, installmentAmount);
        progressRepository.save(progress);

        UUID obligorPartyId = progress.getObligorPartyId();
        ContactInfo contact = contactResolutionService.resolvePartyContact(obligorPartyId);
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        long daysPastDue = dueDate == null ? 0
                : Math.max(0, ChronoUnit.DAYS.between(dueDate, LocalDate.now()));

        Map<String, String> vars = baseVars(contact);
        vars.put("installmentAmount", money(installmentAmount));
        vars.put("installmentNumber", installmentNumber == null ? "" : installmentNumber.toString());
        vars.put("totalInstallments", progress.getTotalInstallments() == null
                ? "" : progress.getTotalInstallments().toString());
        vars.put("dueDate", dueDate == null ? "" : dueDate.toString());
        vars.put("daysPastDue", String.valueOf(daysPastDue));
        vars.put("link", "");

        EventType type = daysPastDue > 0 ? EventType.PAYMENT_OVERDUE : EventType.PAYMENT_REMINDER;
        dispatchService.dispatch(sourceEventId, type, obligorPartyId, contact, preference, vars);
    }

    /**
     * #5 Cuota pagada — {@code payments.payment-applied}. NT-11: aproximación best-effort, no falla
     * si no hay cuota vigente cargada o el monto no la cubre — simplemente no dispara nada.
     */
    public void onPaymentApplied(String sourceEventId, UUID creditAccountId, BigDecimal amount) {
        Optional<CreditAccountProgress> progressOpt = progressRepository.findById(creditAccountId);
        if (progressOpt.isEmpty()) {
            log.debug("No CreditAccountProgress for creditAccountId={} — #5 skipped (NT-11)", creditAccountId);
            return;
        }
        CreditAccountProgress progress = progressOpt.get();
        Optional<Integer> installmentNumber = progress.tryMarkInstallmentPaid(amount);
        progressRepository.save(progress);
        if (installmentNumber.isEmpty()) {
            return; // sin cuota vigente cargada, o el pago no la cubre — no es un error (NT-11)
        }

        ContactInfo contact = contactResolutionService.resolvePartyContact(progress.getObligorPartyId());
        NotificationPreference preference = preferenceRepository.findById(progress.getObligorPartyId()).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("installmentNumber", String.valueOf(installmentNumber.get()));
        vars.put("totalAmount", money(amount));
        Integer remaining = progress.remainingInstallments();
        vars.put("cuotasRestantes", remaining == null ? "algunas" : String.valueOf(remaining));

        dispatchService.dispatch(sourceEventId, EventType.INSTALLMENT_PAID, progress.getObligorPartyId(),
                contact, preference, vars);
    }

    /** #6 Crédito liquidado — {@code credit-portfolio.balance-updated} filtrado a accountStatus=SETTLED. */
    public void onBalanceUpdated(String sourceEventId, UUID creditAccountId, UUID obligorPartyId,
                                  String accountStatus) {
        if (!"SETTLED".equals(accountStatus)) {
            return;
        }
        String productType = progressRepository.findById(creditAccountId)
                .map(CreditAccountProgress::getProductType)
                .orElse("tu crédito");

        ContactInfo contact = contactResolutionService.resolvePartyContact(obligorPartyId);
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("productType", productType);

        dispatchService.dispatch(sourceEventId, EventType.LOAN_SETTLED, obligorPartyId, contact, preference, vars);
    }

    /**
     * Cobranza pide que se le escriba a un deudor — {@code collections.dunning-requested}.
     *
     * <p>El escalón de tono lo decide cobranza, que es la que conoce el estado del caso; aquí sólo
     * se traduce a su tipo de evento para que la política elija canal y la plantilla ponga el texto.
     * Un escalón desconocido no se inventa: se registra y no se manda nada, porque un mensaje de
     * cobranza con el tono equivocado hace más daño que uno que no sale.
     */
    public void onDunningRequested(String sourceEventId, UUID caseId, UUID obligorPartyId,
                                    String step, int daysDelinquent, BigDecimal totalDebt) {
        EventType eventType = dunningEventType(step);
        if (eventType == null) {
            log.warn("Escalón de cobranza desconocido step={} caseId={} — sin aviso", step, caseId);
            return;
        }

        ContactInfo contact = contactResolutionService.resolvePartyContact(obligorPartyId);
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("diasAtraso", String.valueOf(daysDelinquent));
        vars.put("saldoTotal", money(totalDebt));

        dispatchService.dispatch(sourceEventId, eventType, obligorPartyId, contact, preference, vars);
    }

    /**
     * Se cumplió lo prometido — {@code collections.payment-thanks}.
     *
     * <p>{@code daysDelinquent} separa los dos desenlaces: en cero el mensaje cierra —quedó al
     * corriente—; con saldo agradece sin dar por resuelto lo que no lo está. Decirle «quedaste al
     * corriente» a quien sigue debiendo es el error que hace que el siguiente aviso parezca un error
     * del banco.
     */
    public void onPaymentThanks(String sourceEventId, UUID caseId, UUID obligorPartyId,
                                 BigDecimal amount, int daysDelinquent) {
        ContactInfo contact = contactResolutionService.resolvePartyContact(obligorPartyId);
        NotificationPreference preference = preferenceRepository.findById(obligorPartyId).orElse(null);

        Map<String, String> vars = baseVars(contact);
        vars.put("montoPagado", money(amount));
        vars.put("quedaSaldo", daysDelinquent > 0 ? "si" : "no");
        vars.put("diasAtraso", String.valueOf(daysDelinquent));

        dispatchService.dispatch(sourceEventId, EventType.COLLECTION_PAYMENT_THANKS, obligorPartyId,
                contact, preference, vars);
    }

    private static EventType dunningEventType(String step) {
        if (step == null) return null;
        return switch (step) {
            case "RECORDATORIO"   -> EventType.COLLECTION_REMINDER;
            case "COMO_PAGAR"     -> EventType.COLLECTION_HOW_TO_PAY;
            case "ATRASO"         -> EventType.COLLECTION_OVERDUE_NOTICE;
            case "HISTORIAL"      -> EventType.COLLECTION_CREDIT_HISTORY;
            case "OFRECER_AYUDA"  -> EventType.COLLECTION_OFFER_HELP;
            default -> null;
        };
    }

    private Map<String, String> baseVars(ContactInfo contact) {
        Map<String, String> vars = new HashMap<>();
        vars.put("nombre", contact.firstName() == null ? "" : contact.firstName());
        return vars;
    }

    private String money(BigDecimal amount) {
        return amount == null ? "" : String.format("$%,.2f", amount);
    }

    private String percent(BigDecimal rate) {
        return rate == null ? "" : rate.toPlainString();
    }
}
