package com.knowledgeflow.config;

import com.knowledgeflow.security.JwtProperties;
import com.knowledgeflow.security.JwtSecretStartupGuard;
import com.knowledgeflow.security.StaffAuthorities;
import com.knowledgeflow.security.StaffSessionVerifier;
import com.knowledgeflow.security.TokenTypeAwareJwtAuthenticationConverter;
import jakarta.servlet.DispatcherType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class,
        com.knowledgeflow.security.CorsProperties.class,
        com.knowledgeflow.auth.AuthBootstrapProperties.class})
public class SecurityConfig {

    /**
     * Route-level token-type boundary (ADR-006). Authorities are granted by
     * {@link TokenTypeAwareJwtAuthenticationConverter} after the per-request database checks:
     * portal routes accept only CLIENT_PORTAL sessions; every other authenticated route accepts
     * only a full staff session. A staff session that must change its password reaches only the
     * password change, logout-all and /auth/me. Role checks stay in {@code @PreAuthorize}.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   TokenTypeAwareJwtAuthenticationConverter converter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).authenticated()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/bootstrap-admin").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/client-auth/login").permitAll()
                        .requestMatchers("/api/v1/portal/**").hasAuthority(StaffAuthorities.CLIENT_PORTAL)
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me")
                        .hasAnyAuthority(StaffAuthorities.STAFF, StaffAuthorities.STAFF_PASSWORD_CHANGE_ONLY)
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/password", "/api/v1/auth/logout-all")
                        .hasAnyAuthority(StaffAuthorities.STAFF, StaffAuthorities.STAFF_PASSWORD_CHANGE_ONLY)
                        .anyRequest().hasAuthority(StaffAuthorities.STAFF)
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties jwtProperties, JwtSecretStartupGuard secretGuard) {
        OctetSequenceKey jwk = new OctetSequenceKey.Builder(secretKey(jwtProperties))
                .keyID("knowledgeflow-local-hs256")
                .algorithm(JWSAlgorithm.HS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties jwtProperties, JwtSecretStartupGuard secretGuard) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(secretKey(jwtProperties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Signature + exp/nbf (default) + issuer, and exp/sub must be present: a token without an
        // expiry would otherwise never expire. (iat is not checked: Spring's claim converter
        // synthesises a missing iat from exp, so a presence check on it would be ineffective.)
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, Objects::nonNull)));
        return decoder;
    }

    @Bean
    public TokenTypeAwareJwtAuthenticationConverter jwtAuthenticationConverter(
            StaffSessionVerifier staffSessionVerifier) {
        return new TokenTypeAwareJwtAuthenticationConverter(staffSessionVerifier);
    }

    /**
     * Property-driven CORS (knowledgeflow.security.cors.*).
     * Explicit origins by default; wildcard only as an explicit opt-in and
     * never combined with credentials (fails fast at startup).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            com.knowledgeflow.security.CorsProperties corsProperties) {
        if (corsProperties.isWildcard() && corsProperties.allowCredentials()) {
            throw new IllegalStateException(
                    "CORS misconfiguration: wildcard origins cannot be combined with allow-credentials=true");
        }
        CorsConfiguration config = new CorsConfiguration();
        if (corsProperties.isWildcard()) {
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            config.setAllowedOrigins(corsProperties.allowedOriginsOrDefault());
        }
        config.setAllowedMethods(corsProperties.allowedMethodsOrDefault());
        config.setAllowedHeaders(corsProperties.allowedHeadersOrDefault());
        config.setAllowCredentials(corsProperties.allowCredentials());
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    private SecretKey secretKey(JwtProperties jwtProperties) {
        byte[] secret = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(secret, "HmacSHA256");
    }
}
