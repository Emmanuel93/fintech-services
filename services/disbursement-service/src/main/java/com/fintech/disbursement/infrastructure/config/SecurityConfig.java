package com.fintech.disbursement.infrastructure.config;

import com.fintech.disbursement.infrastructure.adapter.in.api.JwtAuthenticationFilter;
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
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Routing y mapeo de empresas: configuración que mueve dinero.
                        .requestMatchers("/api/v1/disbursements/routing/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/disbursements/company-mappings/**").hasRole("ADMIN")
                        // Ingesta directa: la puerta que no es Kafka (DC-6).
                        .requestMatchers(HttpMethod.POST, "/api/v1/disbursements")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR", "SERVICE")
                        .requestMatchers(HttpMethod.POST, "/api/v1/disbursements/*/cancel")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/disbursements/**")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR", "AUDITOR")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
