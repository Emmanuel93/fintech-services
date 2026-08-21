package com.fintech.beneficiary.infrastructure.adapter.in.api;

import com.fintech.beneficiary.application.port.in.PlacementLifecycleUseCase;
import com.fintech.beneficiary.domain.Placement;
import com.fintech.beneficiary.domain.PlacementStatus;
import com.fintech.beneficiary.infrastructure.adapter.in.api.dto.PlacementResponse;
import com.fintech.beneficiary.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.beneficiary.infrastructure.adapter.out.client.PartyClient;
import com.fintech.beneficiary.infrastructure.config.BeneficiaryProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * El KYC de la beneficiaria, ejecutado desde el servidor.
 *
 * <p><b>Esto sustituye a un frontend que todavía no existe.</b> En el diseño, la beneficiaria
 * recibe una liga por WhatsApp y hace sus 7 pasos desde su propio teléfono, en una web pública
 * (`KYC Beneficiario.dc.html`). Mientras esa web no esté construida, ninguna colocación puede
 * avanzar de {@code INVITED} y el flujo del distribuidor no se puede ni probar ni demostrar.
 *
 * <p>Lo que hace es real, no simulado: crea el prospecto {@code INDIVIDUAL} en origination con el
 * consentimiento de buró marcado —que es lo que dispara la consulta a las SIC—, espera a que
 * party-service cree su Party y registra la relación con el distribuidor. Lo único que se salta
 * son las capturas que sólo ella puede hacer: su OTP, las fotos de su INE y su prueba de vida.
 *
 * <p>Está detrás de {@code kyc-simulation-enabled} y **apagado fuera de local**: en cualquier
 * ambiente con datos reales, esto es un bypass de identidad.
 */
@RestController
@RequestMapping("/api/v1/internal/test-support")
@ConditionalOnProperty(prefix = "fintech.beneficiary", name = "kyc-simulation-enabled",
                       havingValue = "true")
@Tag(name = "Soporte de pruebas", description = "Sólo local — sustituye la web de KYC")
class KycSimulationController {

    private static final Logger log = LoggerFactory.getLogger(KycSimulationController.class);

    private final PlacementLifecycleUseCase lifecycle;
    private final OriginationClient origination;
    private final PartyClient party;
    private final BeneficiaryProperties props;

    KycSimulationController(PlacementLifecycleUseCase lifecycle,
                            OriginationClient origination,
                            PartyClient party,
                            BeneficiaryProperties props) {
        this.lifecycle   = lifecycle;
        this.origination = origination;
        this.party       = party;
        this.props       = props;
    }

    @Operation(summary = "Completar el KYC de la beneficiaria",
               description = "Crea su expediente real y deja la colocación esperando decisión.")
    @PostMapping("/placements/{placementId}/complete-kyc")
    ResponseEntity<PlacementResponse> completeKyc(@PathVariable UUID placementId,
                                                  @RequestBody(required = false) KycPayload body) {
        Placement placement = lifecycle.findById(placementId);
        log.info("[simulación] cerrando el KYC de la colocación {}", placementId);

        // Reintentable: si un intento anterior murió a media carga —origination caído, un dato
        // que no pasó validación— la colocación ya quedó en KYC_IN_PROGRESS y volver a arrancarla
        // sería una transición inválida. El KYC de verdad tiene la misma propiedad: la
        // beneficiaria puede cerrar la liga y volver a abrirla.
        if (placement.getStatus() == PlacementStatus.INVITED) {
            lifecycle.startKyc(placementId);
        }

        KycPayload data = body == null ? KycPayload.empty() : body;
        String[] names = splitName(placement.getBeneficiaryFullName());
        String curp = data.curp() != null ? data.curp() : syntheticCurp(names, placement.getBeneficiaryPhone());

        var prospect = origination.createIndividualProspect(new OriginationClient.NewProspect(
                names[0], names[1], names[2],
                curp,
                data.rfc() != null ? data.rfc() : rfcFrom(curp),
                data.dateOfBirth() != null ? data.dateOfBirth() : "1990-06-12",
                data.gender() != null ? data.gender() : "FEMALE",
                data.stateOfBirth() != null ? data.stateOfBirth() : "Ciudad de México",
                placement.getBeneficiaryPhone(),
                data.email() != null ? data.email() : placement.getBeneficiaryPhone() + "@beneficiaria.mx",
                data.street() != null ? data.street() : "Cerrada de Xochicalco",
                data.exteriorNumber() != null ? data.exteriorNumber() : "44",
                data.neighborhood() != null ? data.neighborhood() : "Narvarte Poniente",
                data.municipality() != null ? data.municipality() : "Benito Juárez",
                data.city() != null ? data.city() : "Ciudad de México",
                data.state() != null ? data.state() : "Ciudad de México",
                data.postalCode() != null ? data.postalCode() : "03020",
                "Kredius#Beneficiaria2026"));

        // El Party nace de un evento de Kafka, así que puede tardar un instante en existir.
        var partyRow = party.awaitByProspect(prospect.prospectId(), 15, 400)
                .orElseThrow(() -> new IllegalStateException(
                        "party-service no creó el Party de la beneficiaria a tiempo"));

        String clabe = data.clabe() != null ? data.clabe() : syntheticClabe();
        lifecycle.completeKyc(placementId, prospect.prospectId(), partyRow.partyId(), clabe);
        party.linkBeneficiary(placement.getDistributorPartyId(), partyRow.partyId());

        // El buró lo consulta scoring solo, al ver nacer el prospecto con el consentimiento
        // marcado. Marcar BUREAU_READY aquí es lo que sustituye al listener de la fase 4.
        Placement ready = lifecycle.bureauReady(placementId);
        log.info("[simulación] colocación {} lista para decisión — prospecto {} party {}",
                placementId, prospect.prospectId(), partyRow.partyId());
        return ResponseEntity.ok(PlacementResponse.from(ready));
    }

    private static String[] splitName(String full) {
        String[] parts = full == null ? new String[0] : full.trim().split("\\s+");
        return switch (parts.length) {
            case 0 -> new String[]{"Beneficiaria", "Sin", "Apellido"};
            case 1 -> new String[]{parts[0], "Sin", "Apellido"};
            case 2 -> new String[]{parts[0], parts[1], "Sin"};
            case 3 -> new String[]{parts[0], parts[1], parts[2]};
            default -> new String[]{parts[0] + " " + parts[1], parts[2], parts[3]};
        };
    }

    /**
     * CURP con la forma que exige origination; no pretende ser la real de nadie.
     *
     * <p>Se quitan acentos antes de armarla: «Díaz» produciría una Í y el patrón sólo acepta
     * A-Z. Es el mismo saneo que haría un OCR de verdad al leer la credencial.
     */
    private static String syntheticCurp(String[] names, String phone) {
        String paterno = ascii(names[1]);
        String materno = ascii(names[2]);
        String nombre  = ascii(names[0]);
        String initials = letter(paterno, 0) + vowel(paterno) + letter(materno, 0) + letter(nombre, 0);
        // AAAAdddddd H|M EE CCC d — 18 posiciones exactas.
        return (initials + "900612" + "MDF"
                + letter(paterno, 1) + letter(materno, 1) + letter(nombre, 1)
                + phone.charAt(8) + phone.charAt(9)).toUpperCase();
    }

    /**
     * RFC de persona física a partir de la CURP: sus diez primeras posiciones más homoclave.
     *
     * <p>Las tres de la homoclave las calcula el SAT; aquí se derivan de la propia CURP para que
     * sean estables entre reintentos — un RFC que cambia en cada llamada rompería la
     * idempotencia del alta.
     */
    private static String rfcFrom(String curp) {
        String base = curp.substring(0, 10);
        int hash = Math.abs(curp.hashCode());
        char[] alphabet = "ABCDEFGHIJKLMNPQRSTUVWXYZ".toCharArray();
        return base + alphabet[hash % alphabet.length]
                    + (hash / 10 % 10)
                    + alphabet[(hash / 100) % alphabet.length];
    }

    /** Sin acentos, sin ñ y sin nada que no sea una letra del alfabeto inglés. */
    private static String ascii(String s) {
        if (s == null || s.isBlank()) return "X";
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace("Ñ", "X").replace("ñ", "x")
                .replaceAll("[^A-Za-z]", "");
        return normalized.isBlank() ? "X" : normalized.toUpperCase();
    }

    /** La i-ésima letra, o X si no la hay. Ya viene saneada por {@link #ascii(String)}. */
    private static String letter(String s, int i) {
        return s != null && s.length() > i ? String.valueOf(s.charAt(i)).toUpperCase() : "X";
    }

    /** Primera vocal interna del apellido — la tercera posición de una CURP real. */
    private static String vowel(String s) {
        if (s == null || s.length() < 2) return "X";
        for (char c : s.substring(1).toCharArray()) {
            if ("AEIOU".indexOf(Character.toUpperCase(c)) >= 0) {
                return String.valueOf(Character.toUpperCase(c));
            }
        }
        return "X";
    }

    private static String syntheticClabe() {
        StringBuilder sb = new StringBuilder("012180");
        for (int i = 0; i < 12; i++) sb.append(ThreadLocalRandom.current().nextInt(10));
        return sb.toString();
    }

    /** Todo opcional: lo que no venga se rellena con valores verosímiles. */
    record KycPayload(String curp, String rfc, String dateOfBirth, String gender,
                      String stateOfBirth, String email, String street, String exteriorNumber,
                      String neighborhood, String municipality, String city, String state,
                      String postalCode, String clabe) {

        static KycPayload empty() {
            return new KycPayload(null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }
}
