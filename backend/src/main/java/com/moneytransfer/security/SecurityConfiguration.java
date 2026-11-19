package com.moneytransfer.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneytransfer.shared.api.ApiErrorResponse;
import com.moneytransfer.shared.filter.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            Environment environment,
            ObjectProvider<JwtDecoder> jwtDecoderProvider) throws Exception {
        boolean localProfile = environment.acceptsProfiles(Profiles.of("local"));
        boolean productionProfile = environment.acceptsProfiles(Profiles.of("prod"));
        if (localProfile && productionProfile) {
            throw new IllegalStateException("The local and prod profiles must not be active at the same time.");
        }
        configureRequests(http, objectMapper, localProfile);

        if (localProfile) {
            http.oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(jwt -> jwt.decoder(jwtDecoderProvider.getObject()))
                    .authenticationEntryPoint((request, response, exception) ->
                            writeError(objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                    "UNAUTHENTICATED", "Authentication is required.")));
            return http.build();
        }

        String issuerUri = environment.getProperty("OIDC_ISSUER_URI");
        if (!StringUtils.hasText(issuerUri)) {
            issuerUri = environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
        }
        if (productionProfile && !StringUtils.hasText(issuerUri)) {
            throw new IllegalStateException("OIDC_ISSUER_URI must be configured when the prod profile is active.");
        }
        if (StringUtils.hasText(issuerUri)) {
            String resourceServerIssuer = issuerUri;
            http.oauth2ResourceServer(resourceServer -> resourceServer
                    .jwt(jwt -> jwt.decoder(JwtDecoders.fromIssuerLocation(resourceServerIssuer)))
                    .authenticationEntryPoint((request, response, exception) ->
                            writeError(objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                    "UNAUTHENTICATED", "Authentication is required.")));
        }
        return http.build();
    }

    private void configureRequests(HttpSecurity http, ObjectMapper objectMapper, boolean localProfile) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
                    if (localProfile) {
                        authorize.requestMatchers("/api/v1/demo/session").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                writeError(objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "UNAUTHENTICATED", "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) ->
                                writeError(objectMapper, request, response, HttpServletResponse.SC_FORBIDDEN,
                                        "FORBIDDEN", "You are not allowed to access this resource.")));
    }

    private static void writeError(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(code, message, TraceIdFilter.getTraceId(request)));
    }
}
