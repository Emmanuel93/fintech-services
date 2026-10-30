package com.fintech.beneficiary.publickyc;

import com.fintech.beneficiary.application.KycData;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementLimits;
import com.fintech.beneficiary.domain.VerificationMode;
import com.fintech.shared.banking.ClabeCheckDigit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Datos verosímiles y válidos: cada prueba rompe sólo el campo que le interesa. */
final class KycFixtures {

    /** Una CLABE de Banorte con su dígito verificador correcto. */
    static final String CLABE = ClabeCheckDigit.complete("07218000123456789");
    static final String CURP  = "COHM900612MDFRRR04";
    static final String PHONE = "5541829037";

    private static final PlacementLimits LIMITS = new PlacementLimits(
            new BigDecimal("5000"), new BigDecimal("60000"), 1000, 8, 16, 1);

    private KycFixtures() {}

    static KycData data() {
        return new KycData("María Luisa", "Cortés", "Hernández", CURP, "COHM900612AB1",
                "1990-06-12", "Comerciante", new BigDecimal("12000"),
                "Cerrada de Xochicalco", "44", "Narvarte Poniente", "Benito Juárez",
                "Ciudad de México", "Ciudad de México", "03020", CLABE, null);
    }

    static Placement placement() {
        return placement(UUID.randomUUID());
    }

    static Placement placement(UUID distributor) {
        return Placement.draft(distributor, UUID.randomUUID(), "DL-DIST-STD-V1",
                "María Luisa Cortés Hernández", PHONE, "Clienta de mi tienda",
                new BigDecimal("18000"), 12, new BigDecimal("868.06"),
                VerificationMode.SELF_SERVICE_LINK, Instant.now().plus(7, ChronoUnit.DAYS),
                LIMITS, new BigDecimal("205000"));
    }
}
