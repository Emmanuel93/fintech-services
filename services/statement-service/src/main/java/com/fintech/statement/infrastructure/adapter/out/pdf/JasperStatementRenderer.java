package com.fintech.statement.infrastructure.adapter.out.pdf;

import com.fintech.statement.application.StatementProperties;
import com.fintech.statement.application.StatementProperties.Palette;
import com.fintech.statement.application.port.out.StatementRenderer;
import com.fintech.statement.application.service.assembly.DocumentModel;
import com.fintech.statement.domain.StatementTemplate;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Llena y exporta con JasperReports.
 *
 * <h2>Se compila una vez</h2>
 *
 * Compilar un JRXML cuesta cientos de milisegundos; llenarlo, unos cuantos. Las plantillas —una por
 * producto— se compilan al arrancar y el {@link JasperReport} compilado se comparte entre hilos —Jasper lo
 * permite: cada llenado trae sus parámetros y su fuente de datos—. Una plantilla rota hace que el
 * pod no arranque, que es mejor que descubrirlo en el primer corte.
 *
 * <h2>La marca entra aquí, no en el ensamblador</h2>
 *
 * Las plantillas están escritas con la paleta del design system de Kredius. Antes de compilar, cada color
 * de esa paleta se sustituye por el configurado en {@code fintech.statement.branding.palette}: el JRXML
 * sigue siendo válido para Jaspersoft Studio y el color sigue siendo configurable. El logo se carga una
 * vez y viaja como parámetro {@code LOGO}; si no se puede leer, la plantilla imprime el nombre del emisor.
 *
 * <h2>La versión de la plantilla queda con el documento</h2>
 *
 * Es el nombre más los primeros caracteres del SHA-256 del JRXML ya con la paleta aplicada. Si mañana
 * cambia el diseño o el color, cada PDF sigue diciendo con qué plantilla salió.
 */
@Component
class JasperStatementRenderer implements StatementRenderer {

    private static final Logger log = LoggerFactory.getLogger(JasperStatementRenderer.class);

    /** The renderer fills it, so the assemblers do not have to know it exists. */
    static final String LOGO_PARAMETER = "LOGO";

    private final Map<StatementTemplate, JasperReport> compiled = new EnumMap<>(StatementTemplate.class);
    private final Map<StatementTemplate, String> versions = new EnumMap<>(StatementTemplate.class);
    private final Image logo;

    JasperStatementRenderer(StatementProperties properties) {
        Palette palette = properties.getBranding().getPalette();
        this.logo = loadLogo(properties.getBranding().getLogo());
        for (StatementTemplate template : StatementTemplate.values()) {
            String path = "reports/" + template.file() + ".jrxml";
            try {
                byte[] jrxml = applyPalette(new String(read(path), StandardCharsets.UTF_8), palette)
                        .getBytes(StandardCharsets.UTF_8);
                long t0 = System.nanoTime();
                compiled.put(template, JasperCompileManager.compileReport(new ByteArrayInputStream(jrxml)));
                versions.put(template, template.file() + "@" + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(jrxml)).substring(0, 12));
                log.info("Plantilla {} compilada en {} ms", path, (System.nanoTime() - t0) / 1_000_000);
            } catch (Exception e) {
                throw new IllegalStateException("La plantilla " + path + " no compila", e);
            }
        }
    }

    @Override
    public byte[] render(StatementTemplate template, DocumentModel model) {
        try {
            Collection<Map<String, ?>> rows = new ArrayList<>(model.rows());
            Map<String, Object> parameters = model.parameters();
            parameters.put(LOGO_PARAMETER, logo);
            JasperPrint print = JasperFillManager.fillReport(compiled.get(template), parameters,
                    new JRMapCollectionDataSource(rows));
            return JasperExportManager.exportReportToPdf(print);
        } catch (JRException e) {
            throw new IllegalStateException("No se pudo generar el PDF con la plantilla " + template.file()
                    + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String templateVersion(StatementTemplate template) {
        return versions.get(template);
    }

    /** Los parámetros que la plantilla declara y el ensamblador debe llenar: los de contenido, no la marca. */
    List<String> parametersOf(StatementTemplate template) {
        return Arrays.stream(compiled.get(template).getParameters())
                .filter(p -> !p.isSystemDefined())
                .map(p -> p.getName())
                .filter(n -> !LOGO_PARAMETER.equals(n))
                .toList();
    }

    /** Whether a logo was loaded (the templates print the issuer's name when there is none). */
    boolean hasLogo() {
        return logo != null;
    }

    /**
     * Every color of the design system's palette becomes the configured one. Each role is replaced by its
     * own value, case-insensitively, so swapping two roles or changing one leaves the rest untouched.
     */
    static String applyPalette(String jrxml, Palette palette) {
        Map<String, String> defaults = Palette.defaults().roles();
        Map<String, String> configured = palette.roles();
        String out = jrxml;
        for (Map.Entry<String, String> role : defaults.entrySet()) {
            String wanted = normalize(configured.get(role.getKey()), role.getValue());
            if (!wanted.equalsIgnoreCase(role.getValue())) {
                // A placeholder first, so a configured color that equals another role's default is not replaced twice.
                out = out.replace(role.getValue(), "@@" + role.getKey() + "@@")
                         .replace(role.getValue().toLowerCase(Locale.ROOT), "@@" + role.getKey() + "@@");
            }
        }
        for (Map.Entry<String, String> role : defaults.entrySet()) {
            out = out.replace("@@" + role.getKey() + "@@", normalize(configured.get(role.getKey()), role.getValue()));
        }
        return out;
    }

    private static String normalize(String color, String fallback) {
        if (color == null || color.isBlank()) return fallback;
        String c = color.trim().toUpperCase(Locale.ROOT);
        if (!c.startsWith("#")) c = "#" + c;
        if (!c.matches("#[0-9A-F]{6}")) {
            throw new IllegalStateException("Color de marca inválido '" + color + "': se espera #RRGGBB");
        }
        return c;
    }

    private static Image loadLogo(String location) {
        if (location == null || location.isBlank()) {
            log.info("Sin logo configurado: los estados de cuenta imprimen el nombre del emisor");
            return null;
        }
        try {
            Resource resource = new DefaultResourceLoader().getResource(location);
            try (InputStream in = resource.getInputStream()) {
                Image image = ImageIO.read(in);
                if (image == null) {
                    throw new IOException("no es una imagen que Java sepa leer (PNG o JPEG)");
                }
                log.info("Logo de los estados de cuenta: {}", location);
                return image;
            }
        } catch (IOException e) {
            log.warn("No se pudo leer el logo '{}' ({}): los estados de cuenta imprimen el nombre del emisor",
                    location, e.getMessage());
            return null;
        }
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
