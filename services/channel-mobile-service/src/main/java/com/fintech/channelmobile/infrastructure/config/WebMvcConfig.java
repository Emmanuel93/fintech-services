package com.fintech.channelmobile.infrastructure.config;

import com.fintech.channelmobile.infrastructure.adapter.in.api.MobileAccessAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Engancha el registro de acceso a todas las peticiones del canal.
 *
 * <p>Se registra en un interceptor y no llamando a la bitácora desde cada controlador porque los
 * rastros de auditoría no se pierden por diseño, se pierden por olvido: un endpoint nuevo que
 * nadie instrumentó. Así entra auditado por omisión, y lo que hay que hacer a propósito es
 * excluirlo — no incluirlo.
 */
@Configuration
class WebMvcConfig implements WebMvcConfigurer {

    private final MobileAccessAuditInterceptor accessAuditInterceptor;

    WebMvcConfig(MobileAccessAuditInterceptor accessAuditInterceptor) {
        this.accessAuditInterceptor = accessAuditInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(accessAuditInterceptor).addPathPatterns("/**");
    }
}
