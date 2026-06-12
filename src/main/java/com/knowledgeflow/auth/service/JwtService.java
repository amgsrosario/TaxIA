package com.knowledgeflow.auth.service;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.security.JwtProperties;
import com.knowledgeflow.users.entity.User;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public JwtService(JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
    }

    public AuthResponse issueToken(User user, Organization organization, List<String> roles) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.accessTokenTtlMinutes() * 60);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("organization_id", organization.getId().toString())
                .claim("roles", roles)
                .build();

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
        return new AuthResponse(
                token,
                "Bearer",
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                user.getId(),
                organization.getId(),
                user.getEmail(),
                user.getFullName(),
                roles
        );
    }
}
