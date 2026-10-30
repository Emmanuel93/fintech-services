package com.fintech.beneficiary.infrastructure.adapter.out.kyc;

import com.fintech.beneficiary.application.port.out.BeneficiaryOtpSender;
import com.fintech.beneficiary.domain.PhoneMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entrega del OTP mientras no hay canal real.
 *
 * <p>En este repo WhatsApp y SMS son noop en notifications, así que aquí sólo se deja constancia
 * de que se mandó. <b>El código no se escribe nunca</b>: un OTP en un log es un OTP en el
 * agregador de logs, al alcance de cualquiera con acceso a Grafana. La única excepción es el código
 * fijo de desarrollo, que no es secreto —está en el compose— y que hay que poder ver para probar.
 */
@Component
class LoggingOtpSender implements BeneficiaryOtpSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);

    @Override
    public void send(String phone, String code, boolean devCode) {
        if (devCode) {
            log.info("[otp] código de DESARROLLO {} para {} (no se entrega: canal sin integrar)",
                    code, PhoneMask.of(phone));
        } else {
            log.info("[otp] código enviado a {} (canal sin integrar: WhatsApp/SMS pendientes)",
                    PhoneMask.of(phone));
        }
    }
}
