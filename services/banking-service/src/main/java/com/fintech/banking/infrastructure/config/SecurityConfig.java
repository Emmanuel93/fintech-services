package com.fintech.banking.infrastructure.config;

import com.fintech.banking.infrastructure.adapter.in.api.JwtAuthenticationFilter;
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
 * {@code X-User-Id} / {@code X-Roles}; este servicio confía. No recibe tráfico de internet.
 *
 * <p><strong>El alta de una cuenta propia es ADMIN y nada menos.</strong> Quien puede dar de alta
 * una CLABE ordenante puede, en el siguiente paso, apuntarle una ruta y hacer que el dinero salga
 * por ella. Es el permiso más sensible del servicio, por encima de consultar cualquier saldo.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) { this.jwtFilter = jwtFilter; }

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
                        // La resolución de ruta devuelve la CLABE ordenante COMPLETA — el conector
                        // la necesita para la cadena original. Es tráfico entre servicios, no de
                        // pantalla: por eso SERVICE y no un rol de consulta.
                        .requestMatchers(HttpMethod.POST, "/api/v1/payouts/route")
                                .hasAnyRole("SERVICE", "ADMIN")
                        // Alta y baja de cuentas propias y de rutas: configuración que decide por
                        // dónde sale el dinero.
                        .requestMatchers(HttpMethod.POST, "/api/v1/bank-accounts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payout-routes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/payout-routes/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/bank-accounts/**", "/api/v1/payout-routes/**")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR", "AUDITOR")
                        // La traza no expone CLABEs ni importes de terceros: son ids y estados. Se
                        // abre también a soporte, que es quien recibe la pregunta «¿dónde está mi
                        // dinero?» y hoy la contesta preguntando por chat.
                        .requestMatchers(HttpMethod.GET, "/api/v1/traces/**")
                                .hasAnyRole("ADMIN", "OPS_SUPERVISOR", "AUDITOR", "SUPPORT")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
