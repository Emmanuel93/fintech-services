package com.fintech.stp.infrastructure.adapter.out.http.stub;

import com.fintech.stp.application.port.in.ManageCompanyUseCase;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

/**
 * Registra la llave pública del stub como llave de verificación de cada empresa, al arrancar.
 *
 * <p><strong>Sin esto el stub no sirve para nada.</strong> El stub firma sus respuestas de
 * conciliación de verdad, pero si nadie registra su pública, la verificación de sello falla siempre
 * y ninguna orden llega jamás a liquidada en local ni en CI: el "camino feliz" que documenta el
 * README no se puede ejercitar, y el único escenario que pasaría sería el de sello alterado — por la
 * razón equivocada.
 *
 * <p>Se ejecuta en cada arranque porque el par de llaves del stub se regenera con el proceso. Sólo
 * existe como bean cuando {@code fintech.stp.gateway.mode=stub}, que a su vez sólo arranca con
 * perfiles de ambiente bajo.
 */
@Component
@ConditionalOnProperty(name = "fintech.stp.gateway.mode", havingValue = "stub")
public class StubVerificationKeySeeder {

    private static final Logger log = LoggerFactory.getLogger(StubVerificationKeySeeder.class);
    private static final int VALIDITY_DAYS = 365;
    /** El mismo tamaño que usa el par del stub: la validación de la firma compara longitudes. */
    private static final int TAMANO_LLAVE = 2048;

    private final StubStpGateway stub;
    private final StpCompanyRepository companies;
    private final ManageCompanyUseCase manageCompany;

    public StubVerificationKeySeeder(StubStpGateway stub,
                                     StpCompanyRepository companies,
                                     ManageCompanyUseCase manageCompany) {
        this.stub = stub;
        this.companies = companies;
        this.manageCompany = manageCompany;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        String material = Base64.getEncoder().encodeToString(stub.stubPublicKeySpki());
        Instant now = Instant.now();
        List<StpCompany> all = companies.findAll();

        if (all.isEmpty()) {
            log.info("Stub: no hay empresas registradas todavía. Al dar de alta una, registra "
                    + "manualmente la llave VERIFICATION del stub o reinicia el servicio.");
            return;
        }

        for (StpCompany company : all) {
            try {
                // registerKey retira la anterior antes de insertar; el par del stub cambia en cada
                // arranque, así que esto es una rotación en toda regla.
                manageCompany.registerKey(company.getCompanyId(), "stub-verification",
                        KeyPurpose.VERIFICATION, material, now,
                        now.plus(VALIDITY_DAYS, ChronoUnit.DAYS), "STUB_SEEDER");
                log.info("Stub: llave VERIFICATION registrada para companyId={}", company.getCompanyId());

                // Y la de FIRMA, que es la mitad que faltaba.
                //
                // Con sólo la de verificación, el relay del outbox no podía firmar **ninguna**
                // orden: reintentaba con retroceso exponencial y dejaba el pago parado en PENDING
                // para siempre. El mock existe para ejercitar el camino completo, y sin llave de
                // firma no puede completar el flujo que existe para ejercitar.
                //
                // La privada se genera aquí, en el arranque, y **sólo en modo stub** —esta clase
                // entera es `@ConditionalOnProperty(mode=stub)`—. En un ambiente real la llave de
                // firma es nuestro certificado ante Banxico: se carga del almacén y no la siembra
                // nadie. Que se genere una nueva en cada arranque es lo correcto para un ambiente
                // bajo: no hay ningún secreto que se quede escrito en ningún lado.
                manageCompany.registerKey(company.getCompanyId(), "stub-signing",
                        KeyPurpose.SIGNING, generarPrivadaPkcs8(), now,
                        now.plus(VALIDITY_DAYS, ChronoUnit.DAYS), "STUB_SEEDER");
                log.info("Stub: llave SIGNING registrada para companyId={}", company.getCompanyId());
            } catch (RuntimeException e) {
                log.error("Stub: no se pudieron registrar las llaves de companyId={}: {}",
                        company.getCompanyId(), e.getMessage());
            }
        }
    }

    /** Una privada RSA nueva en PKCS#8, que es el formato que `wrapSigningKey` espera. */
    private static String generarPrivadaPkcs8() {
        try {
            java.security.KeyPairGenerator generador = java.security.KeyPairGenerator.getInstance("RSA");
            generador.initialize(TAMANO_LLAVE);
            return Base64.getEncoder().encodeToString(generador.generateKeyPair().getPrivate().getEncoded());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA no disponible en esta JVM", e);
        }
    }
}
