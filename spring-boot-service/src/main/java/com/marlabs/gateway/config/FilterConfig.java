package com.marlabs.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marlabs.gateway.auth.CallerAuthenticationFilter;
import com.marlabs.gateway.auth.CallerRegistry;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    /**
     * Registers the auth filter against an explicit allow-list of paths.
     *
     * <p>Listing the protected URLs rather than annotating the filter
     * {@code @Component} keeps the boundary visible in one place: a new public
     * endpoint cannot silently inherit authentication, and a new protected one
     * cannot silently skip it.
     */
    @Bean
    public FilterRegistrationBean<CallerAuthenticationFilter> callerAuthenticationFilter(
            CallerRegistry registry, ObjectMapper objectMapper) {

        var registration = new FilterRegistrationBean<>(
                new CallerAuthenticationFilter(registry, objectMapper));
        registration.addUrlPatterns("/answer", "/batches");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("callerAuthenticationFilter");
        return registration;
    }
}
