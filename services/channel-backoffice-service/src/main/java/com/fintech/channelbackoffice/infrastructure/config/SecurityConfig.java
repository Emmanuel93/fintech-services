package com.fintech.channelbackoffice.infrastructure.config;

import com.fintech.channelbackoffice.infrastructure.adapter.in.api.JwtAuthenticationFilter;
import com.fintech.channelbackoffice.application.PermissionsService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Matriz de autorización del backoffice.
 *
 * <p>El gateway ya validó firma, expiración y canal antes de enrutar aquí; este filtro solo traduce
 * los headers que inyectó a un SecurityContext. Lo que se decide en esta clase es <em>quién puede
 * llamar a qué</em>.
 *
 * <p><b>Es la contraparte de <code>apps/shell/src/nav.ts</code> en el frontend, y esa duplicación es
 * intencional:</b> el front oculta módulos para que la consola sea usable, pero ocultar un botón no
 * es una medida de seguridad — quien tenga el token puede llamar la ruta a mano. La regla que manda
 * es esta. Si las dos listas se separan, la que gobierna es la del servidor y el síntoma será un
 * módulo visible que responde 403.
 *
 * <p>Las rutas se declaran por <b>capacidad</b>, no por lista de roles. Con listas, esto era una
 * tercera copia de la matriz —después de la del front y la del catálogo de roles— y las tres se
 * separaron: un AUDITOR tenía {@code portfolio.view}, {@code clients.view} y
 * {@code applications.view} en la matriz, la consola le mostraba los tres módulos, y estas reglas
 * le devolvían 403 en los tres. Ahora hay una sola fuente y la pregunta que se hace aquí es la
 * misma que responde {@code /permissions/me}.
 *
 * <ol>
 *   <li>ADMIN pasa por todo porque la matriz le da todas las capacidades, no por una excepción.</li>
 *   <li>Sin capacidad declarada = cualquier empleado autenticado (la sesión propia).</li>
 *   <li>Todo lo no declarado exige autenticación: deny by default.</li>
 * </ol>
 *
 * <p>Las rutas de los módulos de fase 3/4 se declaran aquí <em>antes</em> de que existan sus
 * endpoints, a propósito: así ninguno puede nacer sin política. Mientras no existan, una petición
 * autorizada devuelve 404 y una no autorizada devuelve 403 — que es justo cómo se verifica la matriz
 * sin tener todavía los controladores.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";

    private final JwtAuthenticationFilter jwtFilter;
    private final PermissionsService permissionsService;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, PermissionsService permissionsService) {
        this.permissionsService = permissionsService;
        this.jwtFilter = jwtFilter;
    }

    /**
     * Una regla de ruta que pregunta a la matriz.
     *
     * <p>La decisión se toma con la {@code Authentication} de la petición, sin tocar el contexto
     * de seguridad: en este punto la cadena aún lo está montando.
     */
    private AuthorizationManager<RequestAuthorizationContext> requires(String capability) {
        return (authentication, context) ->
                new AuthorizationDecision(permissionsService.has(authentication.get(), capability));
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth

                        // ── Público ───────────────────────────────────────────────────────
                        // Login y refresh; el rate limit vive en el gateway.
                        .requestMatchers(HttpMethod.POST, "/auth/staff/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/staff/refresh").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info",
                                         "/actuator/metrics", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()

                        // ── Sesión propia ─────────────────────────────────────────────────
                        // Cualquier empleado puede cerrar su sesión y consultarse a sí mismo.
                        .requestMatchers("/auth/staff/**").authenticated()

                        // ── Permisos propios ──────────────────────────────────────────────
                        // `/permissions/me` no puede exigir capacidad: es *cómo* la consola
                        // averigua cuáles tiene. Gatearlo dejaría a todo el mundo sin módulos.
                        .requestMatchers("/permissions/me").authenticated()

                        // ── Buzón propio ──────────────────────────────────────────────────
                        // Sin capacidad, igual que `/permissions/me`: es el buzón de uno mismo, y
                        // gatearlo dejaría sin campana justo a quien hay que avisarle de sus cosas.
                        .requestMatchers("/notifications", "/notifications/**").authenticated()

                        // ── Dashboard ─────────────────────────────────────────────────────
                        .requestMatchers("/dashboard/**").access(requires("dashboard.view"))

                        // ── Cartera ───────────────────────────────────────────────────────
                        .requestMatchers("/portfolio/**").access(requires("portfolio.view"))

                        // ── Programas de apoyo por contingencia ───────────────────────────
                        // Simular el padrón no es otorgarlo: ver a cuántas cuentas alcanzaría el
                        // apoyo lo puede hacer quien vigila, sin poder mover un vencimiento.
                        // Otorgarlo corre los vencimientos de un segmento entero y sube la reserva
                        // por el paso a STAGE_2 — es una decisión con costo, no una consulta.
                        .requestMatchers(HttpMethod.GET, "/relief-programs/**")
                                .access(requires("portfolio.relief-view"))
                        .requestMatchers("/relief-programs/**").access(requires("portfolio.relief-grant"))

                        // ── Clientes ──────────────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST, "/clients/*/assign-executive")
                                .access(requires("clients.assign-executive"))
                        .requestMatchers("/clients/**").access(requires("clients.view"))

                        // ── Productos ─────────────────────────────────────────────────────
                        // Desviación deliberada del front: allá el *módulo* es de PRODUCT_MANAGER,
                        // pero el catálogo se lee desde cartera y clientes para resolver nombres y
                        // tasas. Leer queda abierto; publicar, activar o retirar no.
                        .requestMatchers(HttpMethod.GET, "/products/**").authenticated()
                        .requestMatchers("/products/**").access(requires("products.manage"))

                        // ── Política de riesgo ────────────────────────────────────────────
                        // Leerla la puede cualquier empleado: saber bajo qué reglas se aprueba un
                        // crédito es lo que permite explicarle a un cliente por qué se le rechazó,
                        // y eso lo hace quien atiende, no quien configura. Escribirla exige la
                        // misma facultad que definir el producto — cambiar un umbral cambia a
                        // quién se le presta, igual que cambiar la tasa.
                        .requestMatchers(HttpMethod.GET, "/scoring/**").authenticated()
                        .requestMatchers("/scoring/**").access(requires("products.manage"))

                        // ── Personal ──────────────────────────────────────────────────────
                        // Sin capacidad propia todavía: administrar personal es de ADMIN y punto.
                        .requestMatchers("/staff/**").hasRole(ADMIN)

                        // ── Estructura comercial ──────────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/sales-org/**").access(requires("salesorg.view"))
                        .requestMatchers("/sales-org/**").access(requires("salesorg.manage"))

                        // ── Solicitudes ───────────────────────────────────────────────────
                        // Entrar al módulo es `view`; analizar el buró y decidir los comprueba el
                        // controlador, que es donde se sabe qué se está pidiendo.
                        // Dictaminar un documento no es verlo: va antes del comodín del módulo.
                        .requestMatchers(HttpMethod.PUT, "/origination/applications/*/documents/*/review")
                                .access(requires("applications.review-documents"))
                        .requestMatchers("/origination/**").access(requires("applications.view"))

                        // ── Beneficiarios · mesa de KYC ───────────────────────────────────
                        // Sólo lectura: verificar identidad no incluye decidir la colocación, que
                        // es de la distribuidora y queda firmada por ella.
                        .requestMatchers(HttpMethod.POST, "/beneficiaries/placements/*/identity-review")
                                .access(requires("beneficiaries.review-identity"))
                        .requestMatchers(HttpMethod.GET, "/beneficiaries/**").access(requires("beneficiaries.view"))

                        // ── Módulos de fases 3/4 — declarados antes de existir ────────────
                        .requestMatchers("/collections/**").access(requires("portfolio.view"))
                        .requestMatchers("/commissions/**").access(requires("dashboard.commercial"))
                        // Parámetros y políticas mueven dinero y decisiones de crédito: se aprueban
                        // a cuatro ojos (fase 4). Hasta entonces, solo ADMIN escribe.
                        .requestMatchers(HttpMethod.GET, "/config/**").hasAnyRole(
                                ADMIN, "RISK_ANALYST", "PRODUCT_MANAGER", "OPS_SUPERVISOR", "AUDITOR")
                        .requestMatchers("/config/**").hasRole(ADMIN)
                        // El auditor solo lee, y lee todo lo que deja rastro.
                        .requestMatchers("/audit/**").access(requires("audit.view"))

                        // ── Contabilidad ──────────────────────────────────────────────────
                        //
                        // Cerrar o reabrir un período y correr la facturación NO son consultas:
                        // mueven el corte contable y emiten CFDI. Van con su propia capacidad, y
                        // van declaradas ANTES del comodín — Spring evalúa en orden, así que un
                        // `/accounting/**` puesto primero se tragaría también los POST.
                        //
                        // El auditor recibe `accounting.view` y no `accounting.close`: auditar es
                        // leer, y no puede implicar poder mover el corte de lo que se audita.
                        .requestMatchers(HttpMethod.POST, "/accounting/periods/**")
                                .access(requires("accounting.close"))
                        .requestMatchers(HttpMethod.POST, "/accounting/billing-runs")
                                .access(requires("accounting.close"))
                        .requestMatchers("/accounting/**").access(requires("accounting.view"))
                        // Las facturas cuelgan de su propia raíz porque el servicio es otro; la
                        // capacidad es la misma: quien puede ver el mayor puede ver lo facturado.
                        .requestMatchers("/invoices/**").access(requires("accounting.view"))

                        // ── Deny by default ───────────────────────────────────────────────
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
