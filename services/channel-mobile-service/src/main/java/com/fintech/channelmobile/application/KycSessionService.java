package com.fintech.channelmobile.application;

import com.fintech.channelmobile.infrastructure.config.ChannelMobileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KycSessionService {

    private static final Logger log = LoggerFactory.getLogger(KycSessionService.class);

    private final ChannelMobileProperties properties;
    private final ConcurrentHashMap<String, KycSessionData> store = new ConcurrentHashMap<>();

    public KycSessionService(ChannelMobileProperties properties) {
        this.properties = properties;
    }

    public String store(KycSessionData data) {
        String folio = generateFolio();
        Instant expiresAt = Instant.now()
                .plusSeconds(properties.getKycSessionExpiryMinutes() * 60L);
        log.info("KYC session stored folio={} phone={} expiresIn={}min",
                folio, data.phone(), properties.getKycSessionExpiryMinutes());
        store.put(folio, new KycSessionData(
                data.phone(), data.nombres(), data.apellidoPaterno(), data.apellidoMaterno(),
                data.curp(), data.rfc(), data.fechaNacimiento(), data.genero(),
                data.estadoNacimiento(), data.email(),
                data.calle(), data.numeroExterior(), data.numeroInterior(),
                data.colonia(), data.municipio(), data.ciudad(), data.estado(), data.codigoPostal(),
                data.aceptaAvisoPrivacidad(), data.aceptaCirculo(),
                expiresAt));
        return folio;
    }

    public Optional<KycSessionData> retrieve(String folioKyc) {
        KycSessionData data = store.get(folioKyc);
        if (data == null) {
            log.warn("KYC session not found folio={}", folioKyc);
            return Optional.empty();
        }
        if (data.isExpired()) {
            store.remove(folioKyc);
            log.warn("KYC session expired folio={}", folioKyc);
            return Optional.empty();
        }
        log.info("KYC session retrieved folio={}", folioKyc);
        return Optional.of(data);
    }

    public void invalidate(String folioKyc) {
        store.remove(folioKyc);
        log.info("KYC session invalidated folio={}", folioKyc);
    }

    @Scheduled(fixedDelay = 120_000)
    void evictExpired() {
        store.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private String generateFolio() {
        String date = DateTimeFormatter.ofPattern("yyyyMM")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return "KYC-" + date + "-" + suffix;
    }
}
