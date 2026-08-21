package com.fintech.stp.infrastructure.adapter.out.http.stub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.stp.application.StpProperties;
import com.fintech.stp.application.port.out.SigningKeyProvider;
import com.fintech.stp.application.port.out.StpCompanyRepository;
import com.fintech.stp.application.port.out.StpGatewayPort;
import com.fintech.stp.domain.BanxicoResponseCode;
import com.fintech.stp.domain.KeyPurpose;
import com.fintech.stp.domain.StpCompany;
import com.fintech.stp.domain.signing.SignatureAlgorithm;
import com.fintech.stp.domain.signing.StpSigner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Doble de STP para local, CI y ambientes bajos.
 *
 * <p><strong>No es un {@code return true}.</strong> Devuelve el eco literal de lo que se le envió y
 * añade sólo lo que del lado del banco no existía al enviar. Y firma de verdad: tiene su propio par
 * de llaves, y su pública se registra como llave de verificación de la empresa. Así, en local, la
 * regla SO-04 se ejercita en lugar de saltarse — si alguien rompe la verificación de sello, revienta
 * aquí y no en producción.
 *
 * <p>Además verifica la firma que le mandamos, como haría STP: si {@code CadenaOriginalBuilder}
 * cambia y rompe el contrato, el stub rechaza la orden.
 */
@Component
@ConditionalOnProperty(name = "fintech.stp.gateway.mode", havingValue = "stub")
public class StubStpGateway implements StpGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(StubStpGateway.class);
    private static final String CEP_BASE = "https://stub.local/cep/";

    private final StubOrderRepository stubOrders;
    private final StpCompanyRepository companyRepository;
    private final SigningKeyProvider signingKeyProvider;
    private final ObjectMapper objectMapper;
    private final StpProperties properties;
    private final Environment environment;

    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis());
    private final KeyPair stubKeyPair;

    public StubStpGateway(StubOrderRepository stubOrders,
                          StpCompanyRepository companyRepository,
                          SigningKeyProvider signingKeyProvider,
                          ObjectMapper objectMapper,
                          StpProperties properties,
                          Environment environment) {
        this.stubOrders = stubOrders;
        this.companyRepository = companyRepository;
        this.signingKeyProvider = signingKeyProvider;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.environment = environment;
        this.stubKeyPair = generateStubKeyPair();
    }

    /**
     * Perfiles donde se admite el stub. Es una <strong>lista blanca</strong>, no una lista negra de
     * nombres de producción.
     *
     * <p>La versión anterior sólo rechazaba los perfiles {@code prod}/{@code production}, y el único
     * despliegue de este repo arranca con {@code SPRING_PROFILES_ACTIVE=docker}: promover ese mismo
     * compose a producción sin poner {@code STP_GATEWAY_MODE=real} habría dejado el stub corriendo
     * con dinero real. Con lista blanca, cualquier perfil no contemplado —o ninguno— impide el
     * arranque, que es el fallo seguro.
     */
    private static final Set<String> NON_PRODUCTION_PROFILES =
            Set.of("local", "dev", "test", "ci", "docker", "qa", "staging");

    /**
     * Guarda de arranque. Un stub de pagos activo en producción es la peor clase de incidente: todo
     * se ve verde y no sale un peso.
     */
    @PostConstruct
    void refuseToRunInProduction() {
        String[] active = environment.getActiveProfiles();
        boolean allowed = active.length > 0 && Arrays.stream(active)
                .allMatch(profile -> NON_PRODUCTION_PROFILES.contains(profile.toLowerCase(Locale.ROOT)));
        if (!allowed) {
            throw new IllegalStateException(
                    "fintech.stp.gateway.mode=stub con perfiles activos " + Arrays.toString(active)
                            + ". El stub de STP sólo arranca con perfiles de ambiente bajo "
                            + NON_PRODUCTION_PROFILES + ". En producción: STP_GATEWAY_MODE=real.");
        }
        log.warn("STP gateway in STUB mode (profiles={}) — no real payments will be dispatched.",
                Arrays.toString(active));
    }

    /** La pública del stub, para registrarla como llave de verificación en el ambiente. */
    public byte[] stubPublicKeySpki() {
        return stubKeyPair.getPublic().getEncoded();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RegistrationResult registerPaymentOrder(PaymentOrderRequest request) {
        simulateLatency();

        StubScenario scenario = StubScenario.forAmount(request.monto());

        if (scenario == StubScenario.TIMEOUT) {
            throw new IllegalStateException("Stub: timeout simulado hacia STP (escenario .10)");
        }

        Boolean signatureValid = verifyIncomingSignature(request);
        if (Boolean.FALSE.equals(signatureValid)) {
            log.error("Stub REJECTS order {}: la firma está ausente o mal formada.", request.claveRastreo());
            return new RegistrationResult(BanxicoResponseCode.VALOR_INVALIDO.code(),
                    "Stub: la firma recibida no es válida");
        }

        BanxicoResponseCode rejection = scenario.registrationRejection();
        long responseId = rejection != null ? rejection.code() : sequence.incrementAndGet();

        // DUPLICATE_TRACKING_KEY persiste igual: la orden existía de un intento anterior, así que
        // tiene que aparecer en la conciliación y terminar liquidada, no en SO-06.
        boolean persist = rejection == null || scenario == StubScenario.DUPLICATE_TRACKING_KEY;
        if (persist && !stubOrders.existsByClaveRastreo(request.claveRastreo())) {
            stubOrders.save(StubOrder.capture(request, scenario, responseId, signatureValid));
        }

        log.info("Stub registered claveRastreo={} scenario={} responseId={}",
                request.claveRastreo(), scenario, responseId);
        return new RegistrationResult(responseId,
                rejection != null ? rejection.description() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationPage queryReconciliation(String stpEmpresa, String tipoOrden, LocalDate businessDate,
                                                   int page, String signature) {
        simulateLatency();

        List<StubOrder> all = stubOrders.findByEmpresaAndBusinessDateOrderByReceivedAt(stpEmpresa, businessDate);
        Instant now = Instant.now();

        List<ReconciliationEntry> entries = new ArrayList<>();
        for (StubOrder order : all) {
            String estado = order.scenario().reconciliationStatus();
            if (estado == null) {
                continue;   // NEVER_SETTLES: nunca aparece, para ejercitar SO-06
            }
            if (order.getReceivedAt().plus(properties.getGateway().getStub().getSettleAfter()).isAfter(now)) {
                continue;   // todavía "en tránsito"
            }
            entries.add(toEntry(order, estado));

            if (order.scenario() == StubScenario.UNMATCHED_ENTRY) {
                entries.add(unmatchedEntry(order));   // SO-03
            }
        }

        int pageSize = properties.getPolling().getPageSize();
        int from = Math.min((page - 1) * pageSize, entries.size());
        int to = Math.min(from + pageSize, entries.size());

        return new ReconciliationPage("0", "OK", entries.size(), entries.subList(from, to));
    }

    private ReconciliationEntry toEntry(StubOrder order, String estado) {
        long tsCaptura = order.getReceivedAt().toEpochMilli();
        long tsLiquidacion = order.getReceivedAt()
                .plus(properties.getGateway().getStub().getSettleAfter()).toEpochMilli();

        // El eco: los mismos valores que se enviaron, más lo que sólo el banco sabe.
        String nombreCep = order.scenario() == StubScenario.NAME_MISMATCH
                ? "NOMBRE DISTINTO AL ENVIADO"
                : order.getNombreBeneficiario();

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("claveRastreo", order.getClaveRastreo());
        raw.put("empresa", order.getEmpresa());
        raw.put("estado", estado);
        raw.put("monto", order.getMonto());
        raw.put("cuentaBeneficiario", order.getCuentaBeneficiario());
        raw.put("cuentaOrdenante", order.getCuentaOrdenante());
        raw.put("nombreBeneficiario", order.getNombreBeneficiario());
        raw.put("nombreOrdenante", order.getNombreOrdenante());
        raw.put("nombreCep", nombreCep);
        raw.put("rfcCep", order.getRfcCurpBeneficiario());
        raw.put("rfcCurpBeneficiario", order.getRfcCurpBeneficiario());
        raw.put("rfcCurpOrdenante", order.getRfcCurpOrdenante());
        raw.put("institucionContraparte", order.getInstitucionContraparte());
        raw.put("institucionOperante", order.getInstitucionOperante());
        raw.put("conceptoPago", order.getConceptoPago());
        raw.put("referenciaNumerica", order.getReferenciaNumerica());
        raw.put("tipoPago", order.getTipoPago());
        raw.put("tipoCuentaBeneficiario", order.getTipoCuentaBeneficiario());
        raw.put("tipoCuentaOrdenante", order.getTipoCuentaOrdenante());
        raw.put("tsCaptura", tsCaptura);
        raw.put("tsLiquidacion", tsLiquidacion);
        raw.put("causaDevolucion", estado.startsWith("D") ? 99 : null);
        raw.put("urlCEP", CEP_BASE + order.getClaveRastreo());

        String rawPayload = serialize(raw);
        String sello = signRaw(rawPayload, order.scenario());

        return new ReconciliationEntry(
                order.getClaveRastreo(), estado,
                estado.startsWith("D") ? "99" : null,
                CEP_BASE + order.getClaveRastreo(), nombreCep, order.getRfcCurpBeneficiario(),
                sello, tsLiquidacion, tsCaptura, order.getMonto(),
                // El stub firma exactamente el payload SIN el sello, que es lo único verificable.
                order.getCuentaBeneficiario(), rawPayload, rawPayload);
    }

    /** SO-03: una orden que nunca enviamos. */
    private ReconciliationEntry unmatchedEntry(StubOrder order) {
        String fake = "ZZ" + order.getBusinessDate().toString().replace("-", "") + "00000000000999";
        Map<String, Object> raw = Map.of("claveRastreo", fake, "estado", "LQ");
        String rawPayload = serialize(raw);
        return new ReconciliationEntry(fake, "LQ", null, CEP_BASE + fake, "BENEFICIARIO DESCONOCIDO",
                null, signRaw(rawPayload, StubScenario.SETTLED), Instant.now().toEpochMilli(),
                Instant.now().toEpochMilli(), BigDecimal.ONE, "000000000000000000",
                rawPayload, rawPayload);
    }

    /** Firma real. SO-04 sólo vale si el sello se puede verificar y se puede romper a propósito. */
    private String signRaw(String rawPayload, StubScenario scenario) {
        String sello = StpSigner.sign(rawPayload, stubKeyPair.getPrivate(), SignatureAlgorithm.SHA256_WITH_RSA);
        if (scenario == StubScenario.INVALID_SIGNATURE) {
            // Alterar un carácter basta: la verificación tiene que fallar.
            return sello.substring(0, sello.length() - 2) + (sello.endsWith("A") ? "B" : "A") + "=";
        }
        return sello;
    }

    /**
     * Comprueba la firma que recibimos, hasta donde el stub honestamente puede.
     *
     * <p><strong>Limitación explícita:</strong> el JSON de {@code ordenPago/registra} lleva 18
     * campos, pero la cadena original que se firma tiene 34. El stub no puede reconstruirla, así
     * que <em>no</em> verifica criptográficamente el sello saliente: comprueba que exista y que sea
     * una firma RSA bien formada del tamaño de la llave. Eso detecta "no se firmó" o "se firmó con
     * basura", que es lo que realmente rompe en un despliegue.
     *
     * <p>La equivalencia byte a byte de la cadena la cubren los vectores de oro de
     * {@code CadenaOriginalBuilderTest}, que es donde corresponde. Lo que sí se ejercita de verdad
     * de punta a punta es la verificación del sello <em>entrante</em> (SO-04), porque ahí el stub
     * firma el payload completo y el servicio lo verifica contra su pública.
     *
     * @return {@code true} bien formada · {@code false} ausente o corrupta · {@code null} sin empresa
     */
    private Boolean verifyIncomingSignature(PaymentOrderRequest request) {
        Optional<StpCompany> company = companyRepository.findAll().stream()
                .filter(c -> c.getStpEmpresa().equals(request.empresa()))
                .findFirst();
        if (company.isEmpty()) {
            return null;
        }
        String firma = request.firma();
        if (firma == null || firma.isBlank()) {
            return Boolean.FALSE;
        }
        try {
            int expectedBytes = signingKeyProvider.activeKeyMetadata(
                    company.get().getCompanyId(), KeyPurpose.SIGNING).getKeySize() / 8;
            return Base64.getDecoder().decode(firma).length == expectedBytes;
        } catch (RuntimeException e) {
            // Base64 mal formado, o la empresa sin llave activa. En ambos casos el stub responde
            // como respondería STP: la firma no es aceptable.
            return Boolean.FALSE;
        }
    }

    private String serialize(Map<String, Object> raw) {
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private void simulateLatency() {
        long millis = properties.getGateway().getStub().getLatency().toMillis();
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(millis / 2 + 1, millis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static KeyPair generateStubKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No se pudo generar el par de llaves del stub", e);
        }
    }
}
