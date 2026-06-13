package com.knowledgeflow.auth.service;

import com.knowledgeflow.auth.dto.AuthResponse;
import com.knowledgeflow.clients.dto.ClientPortalAuthResponse;
import com.knowledgeflow.clients.entity.ClientPortalUser;
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

    /** Token type claim value for organisation user tokens */
    public static final String TOKEN_TYPE_ORG = "ORG_USER";
    /** Token type claim value for client portal user tokens */
    public static final String TOKEN_TYPE_CLIENT_PORTAL = "CLIENT_PORTAL";

    public AuthResponse issueToken(User user, Organization organization, List<String> roles) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.accessTokenTtlMinutes() * 60);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("token_type", TOKEN_TYPE_ORG)
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

    public ClientPortalAuthResponse issueClientPortalToken(ClientPortalUser portalUser) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusSeconds(jwtProperties.accessTokenTtlMinutes() * 60L);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(portalUser.getId().toString())
                .claim("token_type", TOKEN_TYPE_CLIENT_PORTAL)
                .claim("email", portalUser.getEmail())
                .claim("organization_id", portalUser.getOrganization().getId().toString())
                .claim("client_id", portalUser.getClient().getId().toString())
                .build();

        JwsHeader headers = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();

        return new ClientPortalAuthResponse(
                token,
                "Bearer",
                OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC),
                portalUser.getId(),
                portalUser.getClient().getId(),
                portalUser.getOrganization().getId(),
                portalUser.getEmail()
        );
    }
}
