package com.fintech.channelbackoffice.application;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.IdentityClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.OriginationClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Traduce lo que una persona escribe a los identificadores con los que la bitácora indexa.
 *
 * <p>La auditoría guarda {@code partyId} y {@code aggregateId} porque así se relacionan los
 * eventos entre servicios. Quien investiga no llega con un UUID: llega con el teléfono desde el
 * que alguien entró, el correo con el que se dio de alta o el número de contrato del que se
 * queja. Sin esta traducción, el módulo sólo sirve a quien ya sabe la respuesta.
 *
 * <p>El tipo del dato se deduce de su forma —no se le pide al usuario que clasifique lo que
 * busca— y se consulta a la vez a los servicios que podrían conocerlo. Cada resolución que falla
 * se descarta en silencio: buscar es preguntar en varias puertas, y que en una no abran no es un
 * error de la consulta.
 */
@Service
public class AuditSubjectResolver {

    private static final Logger log = LoggerFactory.getLogger(AuditSubjectResolver.class);

    private static final Pattern UUID_RE =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /**
     * El número nacional son diez dígitos, pero llega de varias formas: como se teclea en la
     * consola (10), como lo guarda origination (+52 y 12) y como lo usa identity de usuario (10).
     * Se acepta cualquiera y se prueban todas las variantes contra cada servicio, porque cada uno
     * guardó lo que le llegó y ninguno normalizó al mismo formato.
     */
    private static final Pattern PHONE_RE = Pattern.compile("^\\d{10}$");
    private static final Pattern CURP_RE = Pattern.compile("^[A-Z]{4}\\d{6}[A-Z]{6}[A-Z0-9]{2}$");

    private final OriginationClient originationClient;
    private final PartyClient partyClient;
    private final IdentityClient identityClient;
    private final CreditPortfolioClient creditPortfolioClient;

    public AuditSubjectResolver(OriginationClient originationClient, PartyClient partyClient,
                                IdentityClient identityClient, CreditPortfolioClient creditPortfolioClient) {
        this.originationClient = originationClient;
        this.partyClient = partyClient;
        this.identityClient = identityClient;
        this.creditPortfolioClient = creditPortfolioClient;
    }

    /**
     * A quién y a qué corresponde el texto buscado.
     *
     * @param subject     lo que se escribió: teléfono, correo, CURP, contrato o UUID
     * @param bearerToken el token del solicitante — identity exige rol de auditoría para resolver
     */
    public Resolution resolve(String subject, String bearerToken) {
        String q = subject == null ? "" : subject.trim();
        if (q.isEmpty()) return Resolution.empty();

        Set<UUID> parties = new LinkedHashSet<>();
        Set<UUID> aggregates = new LinkedHashSet<>();
        List<String> actores = new ArrayList<>();
        List<String> comoSeInterpreto = new ArrayList<>();

        // Un UUID puede ser cualquiera de los dos: se prueba como ambos y la
        // bitácora decide cuál de los dos tiene eventos.
        if (UUID_RE.matcher(q).matches()) {
            UUID id = UUID.fromString(q);
            parties.add(id);
            aggregates.add(id);
            comoSeInterpreto.add("identificador");
        }

        String nacional = nationalNumber(q);
        if (nacional != null) {
            comoSeInterpreto.add("teléfono");
            // Dos caminos distintos y ambos válidos: quien ya es cliente tiene
            // credencial en identity; quien apenas se dio de alta, sólo prospecto.
            var owner = identityClient.lookupCredential(bearerToken, nacional);
            if (owner != null && owner.partyId() != null) {
                parties.add(owner.partyId());
                actores.add(owner.username());
            } else {
                actores.add(nacional);
            }
            for (String variante : List.of(nacional, "+52" + nacional, "52" + nacional)) {
                addProspects(parties, aggregates, null, variante, null);
            }
        }

        if (EMAIL_RE.matcher(q).matches()) {
            comoSeInterpreto.add("correo");
            addProspects(parties, aggregates, q, null, null);
        }

        if (CURP_RE.matcher(q.toUpperCase()).matches()) {
            comoSeInterpreto.add("CURP");
            addProspects(parties, aggregates, null, null, q.toUpperCase());
        }

        // Contrato o folio: lo resuelve la cartera, que ya busca por número de
        // contrato. El agregado de una cuenta es su propio id.
        if (comoSeInterpreto.isEmpty() || q.toUpperCase().startsWith("CTR")) {
            try {
                var page = creditPortfolioClient.search(null, null, q, null, null, null, 0, 10, "createdAt,desc");
                var content = page == null ? List.<CreditPortfolioClient.CreditAccountResponse>of() : page.content();
                if (content != null) {
                    content.forEach(a -> {
                        if (a.creditAccountId() != null) aggregates.add(a.creditAccountId());
                        if (a.obligorPartyId() != null) parties.add(a.obligorPartyId());
                    });
                    if (!content.isEmpty()) comoSeInterpreto.add("contrato");
                }
            } catch (Exception ex) {
                log.debug("El texto «{}» no resolvió a un contrato: {}", q, ex.getMessage());
            }
        }

        return new Resolution(List.copyOf(parties), List.copyOf(aggregates), List.copyOf(actores),
                String.join(" o ", comoSeInterpreto));
    }

    /**
     * El número nacional de diez dígitos, o `null` si el texto no es un teléfono.
     *
     * Se acepta con lada país o sin ella —{@code +52 667 303 8098}, {@code 526673038098} y
     * {@code 6673038098} son el mismo número— porque quien investiga copia y pega lo que tiene a
     * la mano, no lo que el servicio guardó.
     */
    private static String nationalNumber(String texto) {
        String d = texto.replaceAll("[^0-9]", "");
        if (PHONE_RE.matcher(d).matches()) return d;
        if (d.length() == 12 && d.startsWith("52")) return d.substring(2);
        if (d.length() == 13 && d.startsWith("521")) return d.substring(3);
        return null;
    }

    /**
     * Añade los prospectos que coincidan, y el party que se les haya creado después.
     *
     * <p>Un prospecto y su party son la misma persona en dos momentos: antes y después de que se
     * le abriera el crédito. Los eventos viejos cuelgan del prospecto y los nuevos del party, así
     * que buscar sólo uno de los dos parte la historia justo donde se vuelve interesante.
     */
    private void addProspects(Set<UUID> parties, Set<UUID> aggregates,
                              String email, String phone, String curp) {
        List<OriginationClient.ProspectDetailResponse> encontrados;
        try {
            encontrados = originationClient.lookupProspects(email, phone, curp);
        } catch (Exception ex) {
            log.debug("Sin prospectos para ese contacto: {}", ex.getMessage());
            return;
        }
        if (encontrados == null) return;
        for (var p : encontrados) {
            if (p.prospectId() == null) continue;
            UUID prospectId = UUID.fromString(p.prospectId());
            parties.add(prospectId);      // origination emite eventos con el prospecto como sujeto
            aggregates.add(prospectId);
            try {
                var party = partyClient.getByProspectId(prospectId);
                if (party != null && party.partyId() != null) parties.add(party.partyId());
            } catch (Exception ex) {
                log.debug("El prospecto {} todavía no tiene party: {}", prospectId, ex.getMessage());
            }
        }
    }

    /** Lo que resultó de traducir el texto. Vacía significa "no se pudo resolver a nadie". */
    public record Resolution(List<UUID> partyIds, List<UUID> aggregateIds, List<String> actors,
                             String interpretedAs) {

        static Resolution empty() {
            return new Resolution(List.of(), List.of(), List.of(), "");
        }

        public boolean isEmpty() {
            return partyIds.isEmpty() && aggregateIds.isEmpty() && actors.isEmpty();
        }
    }
}
