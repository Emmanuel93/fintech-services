package com.fintech.channelbackoffice.application;

import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.CreditPortfolioClient.CreditAccountResponse;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.PartyClient;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.SalesOrgClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * La cartera de una unidad comercial y de todo lo que cuelga de ella.
 *
 * <p>El tablero comercial mostraba «57 ejecutivos» y cero pesos: contaba personas pero no lo que
 * venden. La cartera existía —cien cuentas, cuatro millones y medio— y no aparecía porque se
 * calculaba sólo desde los <b>distribuidores</b>, y en esta red los créditos cuelgan de
 * <b>ejecutivos</b>. Un árbol comercial que no dice cuánto trae cada rama no sirve para dirigir.
 *
 * <p>La cadena que hay que recorrer atraviesa tres servicios y ninguno la conoce entera:
 *
 * <pre>
 *   cuenta ─(obligado)→ party ─(ejecutivo asignado)→ empleado ─(asignación)→ unidad ─(padre)→ …
 *   credit-portfolio        party-service                    sales-org
 * </pre>
 *
 * <p>Se resuelve con <b>tres llamadas</b> y el resto en memoria, no con una por unidad: el árbol
 * tiene ochenta y siete nodos y preguntar por cada uno convertiría abrir un tablero en cien
 * peticiones. A esta escala —cientos de cuentas— sumar aquí es correcto; cuando la cartera crezca,
 * lo que toca es denormalizar el rollup por evento, no paginar este bucle.
 */
@Service
public class CommercialPortfolioService {

    private static final Logger log = LoggerFactory.getLogger(CommercialPortfolioService.class);

    /**
     * Tamaño de página que se pide aguas abajo.
     *
     * <p>Es 100 porque es lo máximo que los servicios de dominio devuelven: pedir más no falla
     * —devuelve 100 igual, sin avisar—, que es exactamente cómo este rollup acabó informando un
     * cuarto de la cartera. Antes aquí decía 2000 y se leía <b>una sola página</b>: el nacional
     * mostraba $1.08M contra los $4.67M del panorama y las dos cifras parecían medir cosas
     * distintas cuando lo que pasaba era que una estaba truncada.
     */
    private static final int PAGINA = 100;

    /**
     * Tope de páginas por consulta. No es una optimización sino un seguro: si un servicio informa
     * mal {@code totalPages}, el bucle tiene que terminar igual en vez de pedir para siempre.
     */
    private static final int MAX_PAGINAS = 100;

    private final SalesOrgClient salesOrgClient;
    private final PartyClient partyClient;
    private final CreditPortfolioClient creditPortfolioClient;

    public CommercialPortfolioService(SalesOrgClient salesOrgClient, PartyClient partyClient,
                                      CreditPortfolioClient creditPortfolioClient) {
        this.salesOrgClient = salesOrgClient;
        this.partyClient = partyClient;
        this.creditPortfolioClient = creditPortfolioClient;
    }

    /**
     * Suma la cartera del subárbol de {@code unitId}.
     *
     * @param asignaciones las asignaciones vigentes del subárbol, que el llamador ya pidió
     * @param cuentasDeDistribuidor ids de cuenta originadas por un distribuidor. El llamador ya las
     *        resolvió para su propio desglose; pasarlas aquí marca el origen <b>rama por rama</b>
     *        sin una segunda vuelta a comisiones
     */
    public Rollup rollup(UUID unitId, List<Map<String, Object>> asignaciones,
                         Set<UUID> cuentasDeDistribuidor) {
        Set<UUID> deDistribuidor = cuentasDeDistribuidor == null ? Set.of() : cuentasDeDistribuidor;
        // 1. El árbol: cada unidad con su padre, para poder subir los montos.
        Map<UUID, UUID> padreDe = new LinkedHashMap<>();
        Map<UUID, String> nombreDe = new LinkedHashMap<>();
        for (Map<String, Object> u : safe(salesOrgClient.subtree(unitId))) {
            UUID id = uuid(u.get("unitId"));
            if (id == null) continue;
            padreDe.put(id, uuid(u.get("parentUnitId")));
            nombreDe.put(id, String.valueOf(u.getOrDefault("name", "")));
        }

        // 2. Quién está en qué unidad. Un empleado, una unidad vigente.
        Map<UUID, UUID> unidadDe = new LinkedHashMap<>();
        for (Map<String, Object> a : asignaciones) {
            if (!"STAFF".equals(a.get("assigneeType"))) continue;
            UUID staff = uuid(a.get("assigneeId"));
            UUID unidad = uuid(a.get("unitId"));
            if (staff != null && unidad != null) unidadDe.putIfAbsent(staff, unidad);
        }
        if (unidadDe.isEmpty()) {
            return Rollup.vacio(unitId);
        }

        // 3. Los clientes con ejecutivo. Se piden todos de una vez y se filtran aquí: una
        //    consulta por ejecutivo serían cincuenta y dos peticiones para armar una pantalla.
        Map<UUID, UUID> ejecutivoDeObligado = new LinkedHashMap<>();
        try {
            for (var p : todasLasPaginas(pag -> {
                var r = partyClient.search(null, null, null, null, pag, PAGINA, "createdAt,desc");
                return r == null ? null : r.content();
            })) {
                if (p.assignedExecutiveId() == null || !unidadDe.containsKey(p.assignedExecutiveId())) continue;
                // Cartera guarda al obligado por su prospectId; party conoce los dos.
                UUID obligado = p.prospectId() != null ? p.prospectId() : p.partyId();
                if (obligado != null) ejecutivoDeObligado.put(obligado, p.assignedExecutiveId());
            }
        } catch (Exception ex) {
            log.warn("Sin clientes para el rollup de {}: {}", unitId, ex.getMessage());
            return Rollup.vacio(unitId);
        }
        if (ejecutivoDeObligado.isEmpty()) {
            return Rollup.vacio(unitId);
        }

        // 4. Las cuentas de esos obligados. Por `search(partyIds)` y no por `batch`: aquélla
        //    busca por id de **cuenta** y aquí lo que se tiene son ids de obligado. Pasarle los
        //    segundos devuelve una lista vacía sin error, que es la peor forma de equivocarse.
        List<CreditAccountResponse> cuentas;
        try {
            cuentas = todasLasPaginas(pag -> {
                var r = creditPortfolioClient.search(null, null, null, null, null,
                        ejecutivoDeObligado.keySet(), pag, PAGINA, "createdAt,desc");
                return r == null ? null : r.content();
            });
        } catch (Exception ex) {
            log.warn("Sin cartera para el rollup de {}: {}", unitId, ex.getMessage());
            return Rollup.vacio(unitId);
        }

        // 5. Cada cuenta suma en su ejecutivo, en su unidad y en cada ancestro hasta la raíz
        //    del subárbol. Sumar sólo en la hoja obligaría a la pantalla a recorrer el árbol
        //    para saber cuánto trae una región, que es la pregunta que se hace primero.
        Map<UUID, Monto> porUnidad = new LinkedHashMap<>();
        Map<UUID, Monto> porEjecutivo = new LinkedHashMap<>();
        Monto total = new Monto();

        for (CreditAccountResponse c : cuentas) {
            if (!"ACTIVE".equals(c.status()) && !"DELINQUENT".equals(c.status())) continue;
            UUID ejecutivo = ejecutivoDeObligado.get(c.obligorPartyId());
            if (ejecutivo == null) continue;

            boolean deTercero = deDistribuidor.contains(c.creditAccountId());
            total.suma(c, deTercero);
            porEjecutivo.computeIfAbsent(ejecutivo, k -> new Monto()).suma(c, deTercero);

            UUID unidad = unidadDe.get(ejecutivo);
            int guarda = 0;   // el árbol no debería tener ciclos; si los tuviera, esto no cuelga
            while (unidad != null && guarda++ < 20) {
                porUnidad.computeIfAbsent(unidad, k -> new Monto()).suma(c, deTercero);
                if (unidad.equals(unitId)) break;
                unidad = padreDe.get(unidad);
            }
        }

        // Los hijos directos: es lo que la pantalla pinta al lado de cada rama.
        List<Map<String, Object>> hijos = new ArrayList<>();
        padreDe.forEach((id, padre) -> {
            if (!unitId.equals(padre)) return;
            Monto m = porUnidad.getOrDefault(id, new Monto());
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("unitId", id.toString());
            fila.put("name", nombreDe.getOrDefault(id, ""));
            fila.putAll(m.asMap());
            hijos.add(fila);
        });
        hijos.sort((a, b) -> new BigDecimal(String.valueOf(b.get("principal")))
                .compareTo(new BigDecimal(String.valueOf(a.get("principal")))));

        Map<String, Map<String, Object>> ejecutivos = new LinkedHashMap<>();
        porEjecutivo.forEach((id, m) -> ejecutivos.put(id.toString(), m.asMap()));

        Map<String, Map<String, Object>> unidades = new LinkedHashMap<>();
        porUnidad.forEach((id, m) -> unidades.put(id.toString(), m.asMap()));

        return new Rollup(unitId, total.asMap(), hijos, ejecutivos, unidades);
    }

    /**
     * Los números de un tramo de cartera.
     *
     * <p>Capital y mora contestan «cuánto se colocó y cuánto está en riesgo», pero no contestan
     * qué está <b>ganando</b> esa rama ni cuánto de eso ya se devengó sin cobrarse. Un director de
     * operación compara sucursales por las cuatro cosas a la vez: dos sucursales con el mismo
     * capital y distinta mora no valen lo mismo, y dos con la misma mora y distinto devengado
     * tampoco.
     *
     * <p>El <b>origen</b> —colocación propia o por distribuidor— se lleva aparte porque es la
     * pregunta de estructura: una rama que coloca a través de terceros tiene otro costo, otro
     * riesgo y otra palanca de dirección que una que coloca con su propia gente. Sumadas en un
     * solo número esa diferencia desaparece justo donde hay que decidir.
     */
    private static final class Monto {
        private int cuentas;
        private int cuentasVencidas;
        private BigDecimal principal = BigDecimal.ZERO;
        private BigDecimal vencido = BigDecimal.ZERO;
        private BigDecimal atrasoTemprano = BigDecimal.ZERO;
        private BigDecimal sano = BigDecimal.ZERO;
        private BigDecimal devengado = BigDecimal.ZERO;
        private BigDecimal moratorios = BigDecimal.ZERO;
        private BigDecimal exigibleVencido = BigDecimal.ZERO;
        private BigDecimal aCobrar = BigDecimal.ZERO;
        private int cuentasDistribuidor;
        private BigDecimal principalDistribuidor = BigDecimal.ZERO;

        void suma(CreditAccountResponse c, boolean porDistribuidor) {
            cuentas++;
            BigDecimal saldo = nz(c.principalBalance());
            principal = principal.add(saldo);
            // Interés ya devengado que todavía no entra a caja. Es ingreso reconocido y no
            // cobrado: crece solo con el tiempo y es lo primero que se pierde si la cuenta cae.
            devengado = devengado.add(nz(c.accruedInterestBalance()));
            moratorios = moratorios.add(nz(c.penaltyBalance()));
            // Lo exigible que ya se pasó de fecha, que no es lo mismo que el capital expuesto:
            // una cuenta de cien mil con una mensualidad vencida de tres mil debe tres mil hoy y
            // expone cien mil al riesgo. Cobranza persigue lo primero; riesgo mide lo segundo.
            exigibleVencido = exigibleVencido.add(nz(c.overdueAmount()));
            aCobrar = aCobrar.add(nz(c.minimumPayment()));

            // ── Escala IFRS-9 / CNBV, la misma que el tablero global ──────────────────────
            // Antes aquí «vencido» era el saldo de cualquier cuenta con un solo día de atraso.
            // Esa lectura no es la oficial y hacía que la mora de la red no cuadrara con la del
            // panorama: la misma cartera daba dos porcentajes distintos según la pantalla, y no
            // había forma de saber cuál llevar a un comité. La vencida oficial es 90+.
            int dpd = c.daysDelinquent();
            if (dpd > 90) {
                cuentasVencidas++;
                vencido = vencido.add(saldo);
            } else if (dpd > 30) {
                atrasoTemprano = atrasoTemprano.add(saldo);
            } else {
                sano = sano.add(saldo);
            }

            if (porDistribuidor) {
                cuentasDistribuidor++;
                principalDistribuidor = principalDistribuidor.add(saldo);
            }
        }

        Map<String, Object> asMap() {
            BigDecimal mora = pct(vencido, principal);
            BigDecimal p = principal.setScale(2, RoundingMode.HALF_UP);
            BigDecimal d = devengado.setScale(2, RoundingMode.HALF_UP);
            BigDecimal mor = moratorios.setScale(2, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accounts", cuentas);
            m.put("delinquentAccounts", cuentasVencidas);
            m.put("principal", p);
            // `overdue` conserva el nombre porque es lo que ya consume la consola, pero su
            // contenido pasa a ser la cartera vencida **oficial** (90+). Renombrarlo habría
            // dejado dos campos con nombres parecidos y significados distintos conviviendo en
            // la misma respuesta, que es peor que un nombre imperfecto.
            m.put("overdue", vencido.setScale(2, RoundingMode.HALF_UP));
            m.put("delinquencyRate", mora);
            m.put("earlyArrears", atrasoTemprano.setScale(2, RoundingMode.HALF_UP));
            m.put("healthy", sano.setScale(2, RoundingMode.HALF_UP));
            // Lo que toca cobrar en el periodo y lo exigible que ya se pasó de fecha.
            m.put("dueThisPeriod", aCobrar.setScale(2, RoundingMode.HALF_UP));
            m.put("pastDueAmount", exigibleVencido.setScale(2, RoundingMode.HALF_UP));
            m.put("accruedInterest", d);
            m.put("penalty", mor);
            // Lo que el cliente debe hoy, no lo que se le prestó: capital + devengado + moratorios.
            m.put("totalDebt", p.add(d).add(mor));
            // Ticket promedio: lo calcula quien suma, no la pantalla. Dividir en el front obliga a
            // repetir la misma división —y el mismo caso de cero cuentas— en cada tabla que lo pinte.
            m.put("averageTicket", cuentas == 0 ? BigDecimal.ZERO
                    : p.divide(BigDecimal.valueOf(cuentas), 2, RoundingMode.HALF_UP));
            m.put("distributorAccounts", cuentasDistribuidor);
            m.put("distributorPrincipal", principalDistribuidor.setScale(2, RoundingMode.HALF_UP));
            m.put("ownAccounts", cuentas - cuentasDistribuidor);
            m.put("ownPrincipal", p.subtract(principalDistribuidor.setScale(2, RoundingMode.HALF_UP)));
            m.put("distributorShare", pct(principalDistribuidor, principal));
            return m;
        }

        /** En porcentaje 0-100, que es como lo pinta la consola. */
        private static BigDecimal pct(BigDecimal parte, BigDecimal total) {
            return total.signum() == 0 ? BigDecimal.ZERO
                    : parte.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
        }

        private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    }

    /** Lo que ve la pantalla: el total del subárbol, el desglose por rama y por persona. */
    public record Rollup(UUID unitId,
                         Map<String, Object> total,
                         List<Map<String, Object>> byChildUnit,
                         Map<String, Map<String, Object>> byExecutive,
                         Map<String, Map<String, Object>> byUnit) {

        static Rollup vacio(UUID unitId) {
            return new Rollup(unitId, new Monto().asMap(), List.of(), Map.of(), Map.of());
        }
    }

    /**
     * Recorre todas las páginas de una consulta aguas abajo, no la primera.
     *
     * <p>Para cuando el llamador no puede pedir "todo": los servicios de dominio topan la página
     * en cien filas y <b>no avisan</b> —pedir dos mil devuelve cien, con un 200 y sin señal de
     * que falta el resto—, así que leer una sola página parece funcionar y devuelve una muestra
     * disfrazada de total. Es un error que no se nota mirando la respuesta, sólo comparando la
     * cifra con otra pantalla.
     *
     * <p>Se detiene cuando llega un lote incompleto, que es la única señal fiable de final que
     * dan las dos APIs; el tope de páginas es el seguro por si una informa mal su tamaño.
     */
    private static <T> List<T> todasLasPaginas(java.util.function.IntFunction<List<T>> pagina) {
        List<T> todo = new ArrayList<>();
        for (int p = 0; p < MAX_PAGINAS; p++) {
            List<T> lote = pagina.apply(p);
            if (lote == null || lote.isEmpty()) break;
            todo.addAll(lote);
            if (lote.size() < PAGINA) break;
        }
        return todo;
    }

    private static <T> List<T> safe(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static UUID uuid(Object v) {
        if (v == null) return null;
        try {
            return UUID.fromString(String.valueOf(v));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
