package com.fintech.channelbackoffice.infrastructure.config;

import com.fintech.channelbackoffice.infrastructure.adapter.in.api.AccessAuditInterceptor;
import com.fintech.channelbackoffice.infrastructure.adapter.in.api.CustomerIdentityResolver;
import com.fintech.channelbackoffice.infrastructure.adapter.in.api.StaffIdentityResolver;
import com.fintech.channelbackoffice.infrastructure.adapter.out.client.AuditClient;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra la bitácora de acceso del backoffice. Excluye lo que no es un acceso de operador
 * (health, docs, errores): esos no llevan identidad y sólo ensuciarían la traza.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuditClient auditClient;
    private final StaffIdentityResolver identityResolver;
    private final CustomerIdentityResolver customerResolver;
    private final ChannelBackofficeProperties properties;

    public WebMvcConfig(AuditClient auditClient, StaffIdentityResolver identityResolver,
                        CustomerIdentityResolver customerResolver,
                        ChannelBackofficeProperties properties) {
        this.auditClient = auditClient;
        this.identityResolver = identityResolver;
        this.customerResolver = customerResolver;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!properties.isAccessAuditEnabled()) {
            return;
        }
        registry.addInterceptor(new AccessAuditInterceptor(auditClient, identityResolver, customerResolver))
                .excludePathPatterns(
                        "/actuator/**",
                        "/error",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html");
    }
}
