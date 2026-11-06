package com.fintech.stp.infrastructure.config;

import com.fintech.stp.infrastructure.adapter.in.api.JwtAuthenticationFilter;
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
 * Header-trust, igual que el resto del monorepo: el gateway valida el JWT RS256 e inyecta
 * {@code X-User-Id} / {@code X-Roles}; este servicio confía.
 *
 * <p>No hay endpoints públicos de negocio: <strong>este servicio no recibe tráfico de internet</strong>.
 * STP no llama a la plataforma — la confirmación de liquidación se obtiene consultando a STP.
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
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Test support (clock shift): only exists with fintech.test-support.enabled=true; the gateway never routes /internal/*.
                        .requestMatchers("/internal/test-support/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Administración del catálogo multi-empresa y del material criptográfico
                        .requestMatchers("/api/v1/stp/companies/**").hasRole("ADMIN")
                        // STP's webhook for credits received on our CLABEs: no JWT, it carries the shared secret
                        // the controller checks (X-Webhook-Token) and the gateway restricts it to STP's addresses.
                        .requestMatchers(HttpMethod.POST, "/api/v1/stp/abonos").permitAll()
                        // Blocking or reactivating a collection CLABE changes where money is accepted: operations.
                        .requestMatchers(HttpMethod.POST, "/api/v1/stp/collection-accounts/**")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR")
                        // Disparo manual del poller: operación, no consulta
                        .requestMatchers(HttpMethod.POST, "/api/v1/stp/poll")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR")
                        // Tesorería (FINANCE) consulta la orden y su CEP desde el backoffice. La vista
                        // va enmascarada y no hay escritura detrás de un GET.
                        .requestMatchers(HttpMethod.GET, "/api/v1/stp/**")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR", "AUDITOR", "FINANCE")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
