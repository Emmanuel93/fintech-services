package com.fintech.identity.infrastructure.config;

import com.fintech.identity.infrastructure.adapter.in.api.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login",
                                         "/api/v1/auth/refresh",
                                         "/api/v1/auth/logout",
                                         "/api/v1/auth/clients/token").permitAll()
                        // El login y el refresh de staff son públicos por definición; el resto del
                        // canal de backoffice exige token, y la administración exige ADMIN.
                        .requestMatchers("/api/v1/auth/staff/login",
                                         "/api/v1/auth/staff/refresh").permitAll()
                        .requestMatchers("/api/v1/auth/staff/**").authenticated()
                        // Leer UN empleado por id lo puede hacer, además de ADMIN, el canal
                        // autenticado con su propia credencial de servicio (SERVICE_DIRECTORY).
                        //
                        // La bitácora debe decir quién actuó, y el canal resolvía ese nombre con el
                        // token del propio empleado: quien no era ADMIN recibía 403 pidiendo su
                        // propio nombre y su entrada quedaba sin identificar. Auditar no puede
                        // depender de los permisos del auditado. Abrirlo con un hasRole más laxo
                        // habría publicado el directorio de personal a toda la plantilla; esto abre
                        // sólo la lectura puntual, y sólo a un sujeto que no es una persona.
                        //
                        // El orden importa: la regla específica va antes que el comodín, que sigue
                        // exigiendo ADMIN para el directorio completo y para todas las escrituras.
                        .requestMatchers(HttpMethod.GET, "/api/v1/staff/{staffUserId}")
                                .hasAnyRole("ADMIN", "SERVICE_DIRECTORY")
                        .requestMatchers("/api/v1/staff/**").hasRole("ADMIN")
                        // Leer el catálogo de roles no exige sesión: el canal lo consulta al
                        // arrancar, cuando todavía no hay ningún usuario, y identity no se publica
                        // fuera de la red interna —el gateway sólo expone al BFF—. Lo que se
                        // devuelve es la política, no datos de nadie.
                        .requestMatchers(HttpMethod.GET, "/api/v1/roles", "/api/v1/roles/**").permitAll()
                        // Cambiarla sí: va con el token de quien la cambia, y sólo ADMIN.
                        .requestMatchers("/api/v1/roles/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/validate").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/credentials").hasRole("ADMIN")
                        // Atribución para la bitácora: sólo un rol de auditoría pregunta
                        // de quién es un número de acceso, y la respuesta no lleva secretos.
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/credentials/lookup")
                                .hasAnyRole("ADMIN", "AUDITOR")
                        .requestMatchers("/api/v1/auth/clients/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/auth/mfa/verify").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                         "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
