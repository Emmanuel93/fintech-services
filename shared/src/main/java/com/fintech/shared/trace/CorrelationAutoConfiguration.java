package com.fintech.shared.trace;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The correlation id over HTTP, in every service: taken from (or given to) each incoming request, and passed on by
 * the HTTP clients built from Spring Boot's builders ({@code RestClient}, {@code RestTemplate}, {@code WebClient}).
 * A client built by hand ({@code WebClient.builder()}) does not get it and has to add the header itself.
 */
@AutoConfiguration
public class CorrelationAutoConfiguration {

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        // Before security and the services' own filters: whatever they log already carries the id.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public RestClientCustomizer correlationRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String id = Correlation.current();
            if (id != null && !request.getHeaders().containsKey(Correlation.HTTP_HEADER)) {
                request.getHeaders().add(Correlation.HTTP_HEADER, id);
            }
            return execution.execute(request, body);
        });
    }

    @Bean
    public RestTemplateCustomizer correlationRestTemplateCustomizer() {
        return template -> template.getInterceptors().add((request, body, execution) -> {
            String id = Correlation.current();
            if (id != null && !request.getHeaders().containsKey(Correlation.HTTP_HEADER)) {
                request.getHeaders().add(Correlation.HTTP_HEADER, id);
            }
            return execution.execute(request, body);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(WebClient.class)
    static class WebClientCorrelation {

        @Bean
        WebClientCustomizer correlationWebClientCustomizer() {
            // The filter runs when the request is sent; with `.block()` that is the caller's thread and its log context.
            return builder -> builder.filter((request, next) -> {
                String id = Correlation.current();
                if (id == null || request.headers().containsKey(Correlation.HTTP_HEADER)) {
                    return next.exchange(request);
                }
                return next.exchange(ClientRequest.from(request).header(Correlation.HTTP_HEADER, id).build());
            });
        }
    }
}
