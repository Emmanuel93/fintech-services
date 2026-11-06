package com.fintech.documents.pdf;

import com.fintech.documents.Brand;
import com.fintech.documents.DocumentModel;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles a service's JRXML templates once, in its brand's colors, and fills them into PDFs.
 *
 * <p>Each template is known by a key (the service's own enum name) and lives on the classpath at
 * {@code reports/<file>.jrxml}. Its version is the template's name plus the hash of what was compiled, palette
 * included: a document stores the version it was printed with, and a regeneration says whether the design changed.
 * The logotype goes in as the {@code LOGO} parameter; without one, the templates print the issuer's name.
 */
public final class JasperDocumentEngine {

    private static final Logger log = LoggerFactory.getLogger(JasperDocumentEngine.class);

    public static final String LOGO_PARAMETER = "LOGO";

    private final Map<String, JasperReport> compiled = new LinkedHashMap<>();
    private final Map<String, String> versions = new LinkedHashMap<>();
    private final Image logo;

    /**
     * @param templates  key → file name (without {@code reports/} nor {@code .jrxml})
     * @param palette    the configured colors by role; missing roles keep the design system's
     * @param logo       a resource location ({@code classpath:…}, {@code file:…}); blank prints the issuer's name
     */
    public JasperDocumentEngine(Map<String, String> templates, Map<String, String> palette, String logo) {
        this.logo = loadLogo(logo);
        Map<String, String> defaults = Brand.defaultRoles();
        for (Map.Entry<String, String> template : templates.entrySet()) {
            String path = "reports/" + template.getValue() + ".jrxml";
            try {
                byte[] jrxml = Brand.applyPalette(new String(read(path), StandardCharsets.UTF_8), defaults, palette)
                        .getBytes(StandardCharsets.UTF_8);
                long t0 = System.nanoTime();
                compiled.put(template.getKey(), JasperCompileManager.compileReport(new ByteArrayInputStream(jrxml)));
                versions.put(template.getKey(), template.getValue() + "@" + HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(jrxml)).substring(0, 12));
                log.info("Plantilla {} compilada en {} ms", path, (System.nanoTime() - t0) / 1_000_000);
            } catch (Exception e) {
                throw new IllegalStateException("La plantilla " + path + " no compila", e);
            }
        }
    }

    public byte[] render(String key, DocumentModel model) {
        JasperReport report = compiled.get(key);
        if (report == null) throw new IllegalArgumentException("No hay plantilla compilada para " + key);
        try {
            Collection<Map<String, ?>> rows = new ArrayList<>(model.rows());
            Map<String, Object> parameters = model.parameters();
            parameters.put(LOGO_PARAMETER, logo);
            JasperPrint print = JasperFillManager.fillReport(report, parameters, new JRMapCollectionDataSource(rows));
            return JasperExportManager.exportReportToPdf(print);
        } catch (JRException e) {
            throw new IllegalStateException("No se pudo generar el PDF con la plantilla " + key + ": " + e.getMessage(), e);
        }
    }

    public String version(String key) {
        return versions.get(key);
    }

    /** The parameters a template declares, the logotype aside: what an assembler must fill. */
    public List<String> parametersOf(String key) {
        return Arrays.stream(compiled.get(key).getParameters())
                .filter(p -> !p.isSystemDefined())
                .map(p -> p.getName())
                .filter(n -> !LOGO_PARAMETER.equals(n))
                .toList();
    }

    public boolean hasLogo() {
        return logo != null;
    }

    private static Image loadLogo(String location) {
        if (location == null || location.isBlank()) {
            log.info("Sin logo configurado: los documentos imprimen el nombre del emisor");
            return null;
        }
        try {
            Resource resource = new DefaultResourceLoader().getResource(location);
            try (InputStream in = resource.getInputStream()) {
                Image image = ImageIO.read(in);
                if (image == null) {
                    throw new IOException("no es una imagen que Java sepa leer (PNG o JPEG)");
                }
                log.info("Logo de los documentos: {}", location);
                return image;
            }
        } catch (IOException e) {
            log.warn("No se pudo leer el logo '{}' ({}): los documentos imprimen el nombre del emisor",
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
