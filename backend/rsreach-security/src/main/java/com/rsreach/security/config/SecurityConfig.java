package com.rsreach.security.config;

import com.rsreach.security.auth.NoOpPrincipalLoader;
import com.rsreach.security.auth.PrincipalLoader;
import com.rsreach.security.jwt.JwtAuthenticationFilter;
import com.rsreach.security.jwt.JwtProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@AutoConfiguration
@EnableMethodSecurity
@EnableConfigurationProperties({
        RsReachSecurityProperties.class,
        JwtProperties.class,
        WebhookSecurityProperties.class
})
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Fallback for worker/scheduler processes that do not provide a real principal loader.
     * The API module overrides this with {@code CompositePrincipalLoader}.
     */
    @Bean
    @ConditionalOnMissingBean(PrincipalLoader.class)
    PrincipalLoader principalLoader() {
        return new NoOpPrincipalLoader();
    }

    @Bean
    @Order(1)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RsReachSecurityProperties securityProperties
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/api/v1/system/health"
                    ).permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/v1/webhooks/**").permitAll();
                    auth.requestMatchers(HttpMethod.POST,
                            "/api/v1/app/auth/register",
                            "/api/v1/app/auth/login",
                            "/api/v1/app/auth/refresh",
                            "/api/v1/app/auth/logout",
                            "/api/v1/app/auth/password-reset/request",
                            "/api/v1/app/auth/password-reset/confirm",
                            "/api/v1/platform/auth/login",
                            "/api/v1/platform/auth/mfa/verify",
                            "/api/v1/platform/auth/refresh",
                            "/api/v1/platform/auth/logout",
                            "/api/v1/webhooks/**"
                    ).permitAll();
                    if (securityProperties.isPermitUnauthenticatedApi()) {
                        auth.requestMatchers(
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/api/v1/system/**"
                        ).permitAll();
                    }
                    auth.requestMatchers("/api/v1/app/**").authenticated();
                    auth.requestMatchers("/api/v1/platform/**").authenticated();
                    auth.anyRequest().denyAll();
                })
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
