package com.fintech.compliance.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Header-trust, como el resto del monorepo: el gateway valida el JWT e inyecta {@code X-User-Id} y
 * {@code X-Roles}; este servicio confía y no recibe tráfico de internet.
 *
 * <p><b>Adjudicar es el permiso más sensible de aquí.</b> Quien puede despejar una coincidencia
 * puede dejar pasar a alguien que está en una lista de sancionados, y eso no se deshace. Por eso el
 * agregado exige autor y argumento en cada adjudicación: la autorización dice <i>quién puede</i>, y
 * el argumento dice <i>por qué lo hizo</i> — hacen falta las dos.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/error",
                                "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .anyRequest().permitAll())
                .build();
    }
}
