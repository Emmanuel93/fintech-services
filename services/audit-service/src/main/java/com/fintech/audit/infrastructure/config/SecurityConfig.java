package com.fintech.audit.infrastructure.config;

import com.fintech.audit.infrastructure.adapter.in.api.JwtAuthenticationFilter;
import org.springframework.http.HttpMethod;
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
                        .requestMatchers("/actuator/health", "/actuator/info", "/actuator/metrics", "/actuator/prometheus").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        // Ingesta de acceso: la escribe el canal por cada empleado que navega, no sólo
                        // los auditores. Sigue exigiendo identidad válida (X-User-Id del gateway); es
                        // escritura append-only, no lectura de la bitácora.
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/access").authenticated()
                        // Leer la bitácora sigue restringido a roles de auditoría.
                        // Reprocesar o descartar un mensaje fallido mueve la operación: administración u operación.
                        .requestMatchers(HttpMethod.POST, "/api/v1/audit/failures/**").hasAnyRole("ADMIN", "OPS")
                        .requestMatchers("/api/v1/audit/failures/**").hasAnyRole("AUDITOR", "REGULATOR", "ADMIN", "OPS")
                        .requestMatchers("/api/v1/audit/**").hasAnyRole("AUDITOR", "REGULATOR", "ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
