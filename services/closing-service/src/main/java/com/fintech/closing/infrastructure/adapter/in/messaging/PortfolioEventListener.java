package com.fintech.closing.infrastructure.adapter.in.messaging;

import com.fintech.closing.application.service.AccountProjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Cómo se enteran los cierres de que existe una cuenta.
 *
 * <h2>Lo que esto arregla</h2>
 *
 * El motor de cortes estaba completo —deriva el calendario, lo persiste, sella el ciclo y publica el
 * exigible— y <b>no tenía una sola entrada</b>: {@code adapter/in} estaba vacío, cero
 * {@code @KafkaListener}, y {@code AccountProjectionService} no tenía ningún llamador de producción.
 * La tabla {@code account_close_profiles} se poblaba <b>únicamente desde las pruebas</b>, así que en
 * producción nacía vacía y el cierre diario no encontraba nada que cerrar.
 *
 * <p>El README del servicio ya afirmaba consumir estos tópicos. No los consumía.
 *
 * <h2>Por qué la periodicidad llega aquí y no se pregunta después</h2>
 *
 * {@code paymentFrequency} viaja en el evento de activación, y es lo que decide cada cuánto corta la
 * cuenta: semanal, quincenal o mensual, según lo configure el producto. Se guarda en el perfil al
 * activar porque <b>el cierre no consulta cartera en línea</b> — leerla dentro de la ventana rompería
 * su aislamiento, que es la razón por la que {@code CutoffRule} existe en vez de leer
 * {@code installments.due_date}.
 *
 * <h2>Y por qué un solo componente para los tres</h2>
 *
 * Los tres eventos alimentan la <b>misma</b> proyección y ninguno tiene lógica propia: son
 * traducción de payload a caso de uso. Separarlos en tres clases multiplicaría la ceremonia sin
 * añadir una decisión.
 */
@Component
public class PortfolioEventListener {

    private static final Logger log = LoggerFactory.getLogger(PortfolioEventListener.class);

    /**
     * La fecha de activación se interpreta en hora de México, no en la del servidor.
     *
     * <p>El evento trae un {@code Instant}, y de él sale el <b>día</b> al que se ancla todo el
     * calendario de cortes. Con la zona del proceso, una activación de las 19:00 en México cae al día
     * siguiente si el servidor va en UTC — y toda la cuenta cortaría un día corrido para siempre.
     */
    private static final ZoneId ZONA_DE_NEGOCIO = ZoneId.of("America/Mexico_City");

    private final AccountProjectionService projection;

    public PortfolioEventListener(AccountProjectionService projection) {
        this.projection = projection;
    }

    @KafkaListener(
            topics = "credit-portfolio.credit-account-activated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "closingAccountActivatedListenerContainerFactory")
    public void onAccountActivated(CreditAccountActivatedPayload event) {
        if (event == null || event.creditAccountId() == null) return;

        LocalDate activatedOn = event.activatedAt() == null
                ? LocalDate.now(ZONA_DE_NEGOCIO)
                : event.activatedAt().atZone(ZONA_DE_NEGOCIO).toLocalDate();

        log.info("credit-account-activated cuenta={} producto={} cadencia={} plazo={}",
                event.creditAccountId(), event.productType(),
                event.paymentFrequency(), event.termPeriods());

        projection.onAccountActivated(
                event.creditAccountId(), event.obligorPartyId(), event.productType(),
                event.productBehavior(), event.originUnitCode(), activatedOn,
                event.paymentFrequency(), event.termPeriods(),
                event.nominalRate(), event.principalBalance());
    }

    @KafkaListener(
            topics = "credit-portfolio.balance-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "closingBalanceUpdatedListenerContainerFactory")
    public void onBalanceUpdated(BalanceUpdatedPayload event) {
        if (event == null || event.creditAccountId() == null) return;

        // Sin orgUnitCode: este evento no lo trae, y `learnOrgUnit` sólo lo aprende si falta.
        projection.onBalanceUpdated(event.creditAccountId(), event.principalBalance(),
                event.totalDebt(), event.accountStatus(), event.balanceVersion(), null);
    }

    @KafkaListener(
            topics = "credit-portfolio.delinquency-status-updated",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "closingDelinquencyListenerContainerFactory")
    public void onDelinquencyUpdated(DelinquencyStatusUpdatedPayload event) {
        if (event == null || event.creditAccountId() == null) return;
        projection.onDelinquencyUpdated(event.creditAccountId(), event.daysDelinquent());
    }
}
