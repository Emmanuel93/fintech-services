package com.fintech.statement.infrastructure.adapter.out.pdf;

import com.fintech.statement.domain.StatementTemplate;
import net.sf.jasperreports.engine.DefaultJasperReportsContext;
import net.sf.jasperreports.engine.JRPropertiesUtil;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.xml.JRXmlLoader;
import net.sf.jasperreports.jdt.JRJdtCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las plantillas compilan con el compilador que viaja en el jar, no con el del JDK.
 *
 * <p>La imagen corre con un JRE: sin javac. En las pruebas siempre hay JDK, así que una plantilla
 * podía compilar aquí y tumbar el pod al arrancar —pasó—. Esta prueba compila con JDT a propósito.
 */
class TemplateCompilerTest {

    @Test
    @DisplayName("Jasper está configurado para compilar con JDT")
    void elCompiladorEsJdt() {
        String compilador = JRPropertiesUtil.getInstance(DefaultJasperReportsContext.getInstance())
                .getProperty("net.sf.jasperreports.compiler.java");
        assertThat(compilador).isEqualTo(JRJdtCompiler.class.getName());
    }

    @Test
    @DisplayName("la plantilla de cada producto compila con JDT sobre Java 21")
    void lasPlantillasCompilanConJdt() throws Exception {
        JRJdtCompiler jdt = new JRJdtCompiler(DefaultJasperReportsContext.getInstance());
        for (StatementTemplate template : StatementTemplate.values()) {
            try (InputStream jrxml = getClass().getResourceAsStream("/reports/" + template.file() + ".jrxml")) {
                JasperReport compilado = jdt.compileReport(JRXmlLoader.load(jrxml));
                assertThat(compilado).as(template.file()).isNotNull();
            }
        }
    }
}
