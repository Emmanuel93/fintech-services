package com.fintech.beneficiary.infrastructure.config;

import com.fintech.beneficiary.infrastructure.adapter.in.api.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Dos superficies con dos modelos de autenticación distintos en un solo servicio.
 *
 * <ul>
 *   <li><b>Privada</b> ({@code /api/v1/placements}, {@code /api/v1/beneficiaries}) — el
 *       distribuidor, detrás del JWT que valida el gateway y que llega como {@code X-User-Id}.</li>
 *   <li><b>Pública</b> ({@code /api/v1/beneficiary/public/**}) — la beneficiaria, sin JWT y sin
 *       cuenta. Está en {@code permitAll} porque Spring Security no tiene nada que validar aquí:
 *       la credencial es el token de invitado de la liga, y quien lo revisa es el filtro de token
 *       de la fase 2, no esta cadena. Marcarlo {@code authenticated()} rompería el flujo entero;
 *       dejarlo abierto sin ese filtro dejaría el servicio abierto de par en par.</li>
 * </ul>
 */
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
                        .requestMatchers("/actuator/health", "/actuator/info",
                                         "/actuator/metrics", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                         "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // El flujo web de la beneficiaria: sin JWT, autorizado por token de invitado.
                        .requestMatchers("/api/v1/beneficiary/public/**").permitAll()
                        // Soporte de pruebas: sustituye la web de KYC de la beneficiaria, que no
                        // tiene sesión que presentar. No hay riesgo de exponerlo sin querer — el
                        // controlador ni siquiera existe como bean si la bandera está apagada.
                        .requestMatchers("/api/v1/internal/test-support/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
