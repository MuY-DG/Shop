package org.muybaby.shopserver.security;

import org.muybaby.shopserver.admin.log.web.AdminSystemLogFilter;
import org.muybaby.shopserver.analytics.AppUserDailyActivityFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint apiAuthenticationEntryPoint,
            ApiAccessDeniedHandler apiAccessDeniedHandler,
            TokenAuthenticationFilter tokenAuthenticationFilter,
            AppUserDailyActivityFilter appUserDailyActivityFilter,
            AdminSystemLogFilter adminSystemLogFilter
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(apiAuthenticationEntryPoint)
                        .accessDeniedHandler(apiAccessDeniedHandler))
                .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(appUserDailyActivityFilter, TokenAuthenticationFilter.class)
                .addFilterAfter(adminSystemLogFilter, AppUserDailyActivityFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(
                                "/admin/auth/login",
                                "/admin/auth/refresh",
                                "/app/auth/login",
                                "/app/auth/refresh",
                                "/app/health",
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**",
                                "/realtime",
                                "/wxpay/**",
                                "/wechat/**"
                        ).permitAll()
                        .requestMatchers(HttpMethod.POST, "/app/analytics/events/batch").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/app/product/categories", "/app/product/filter-facets",
                                "/app/product/spus", "/app/product/spus/*",
                                "/app/product/spus/*/reviews",
                                "/app/home", "/app/home/banners", "/app/contact",
                                "/app/customer-service/presence").permitAll()
                        .requestMatchers("/admin/**", "/app/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    FilterRegistrationBean<AdminSystemLogFilter> adminSystemLogFilterRegistration(
            AdminSystemLogFilter adminSystemLogFilter
    ) {
        FilterRegistrationBean<AdminSystemLogFilter> registration =
                new FilterRegistrationBean<>(adminSystemLogFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
