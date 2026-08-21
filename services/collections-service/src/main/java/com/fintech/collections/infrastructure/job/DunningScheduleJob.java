package com.fintech.collections.infrastructure.job;

import com.fintech.collections.application.service.DunningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Corre el ciclo de cobranza automática una vez al día, a las 10:00.
 *
 * <p>La hora no es arbitraria: cae dentro de la ventana permitida con margen por los dos lados
 * —arrancar a las 08:00 en punto deja la corrida a merced de que el reloj del contenedor vaya un
 * minuto adelantado— y a media mañana, que es cuando un recordatorio de pago se lee.
 *
 * <p>Va después del job de promesas rotas (08:00), y es deliberado: una promesa que venció anoche
 * tiene que estar marcada como rota antes de que la cadencia decida a quién le escribe, o el caso
 * seguiría silenciado un día más por un compromiso que ya se incumplió.
 */
@Component
public class DunningScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(DunningScheduleJob.class);

    private final DunningService dunningService;

    public DunningScheduleJob(DunningService dunningService) {
        this.dunningService = dunningService;
    }

    @Scheduled(cron = "0 0 10 * * *")
    public void run() {
        log.info("DunningScheduleJob starting");
        int enviados = dunningService.runDailyCycle();
        log.info("DunningScheduleJob finished requests={}", enviados);
    }
}
