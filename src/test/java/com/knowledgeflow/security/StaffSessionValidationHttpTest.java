package com.knowledgeflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.knowledgeflow.billing.entity.CommercialPlan;
import com.knowledgeflow.billing.entity.OrganizationPlan;
import com.knowledgeflow.billing.enums.PlanType;
import com.knowledgeflow.billing.repository.CommercialPlanRepository;
import com.knowledgeflow.billing.repository.OrganizationPlanRepository;
import com.knowledgeflow.clients.dto.ClientCreateRequest;
import com.knowledgeflow.clients.dto.ClientDetailResponse;
import com.knowledgeflow.clients.dto.ClientPortalUserCreateRequest;
import com.knowledgeflow.clients.service.ClientPortalUserService;
import com.knowledgeflow.clients.service.ClientService;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-006 per-request validation of staff tokens, end to end over HTTP with real Bearer tokens:
 * tv, issuer, expiry, signature, token_type boundary, user/membership state and DB-authoritative
 * roles. Every staff failure is 401; a token of the wrong type on a route is 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StaffSessionValidationHttpTest extends StaffSecurityHttpTestSupport {

    private static final String ME = "/api/v1/auth/me";
    private static final String ADMIN_ONLY = "/api/v1/admin/users";
    private static final String STAFF_ANY_ROLE = "/api/v1/clients";
    private static final String PORTAL_ME = "/api/v1/portal/me";

    @Autowired JwtEncoder jwtEncoder;
    @Autowired JwtDecoder jwtDecoder;
    @Autowired JwtProperties jwtProperties;
    @Autowired ClientService clientService;
    @Autowired ClientPortalUserService clientPortalUserService;
    @Autowired CommercialPlanRepository commercialPlanRepository;
    @Autowired OrganizationPlanRepository organizationPlanRepository;

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> staffClaims(User user, UUID organizationId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getId().toString());
        claims.put("token_type", "ORG_USER");
        claims.put("email", user.getEmail());
        claims.put("organization_id", organizationId.toString());
        claims.put("roles", List.of("ADMIN"));
        claims.put("tv", reload(user).getTokenVersion());
        return claims;
    }

    private String sign(JwtEncoder encoder, Map<String, Object> claims, String issuer, Instant iat, Instant exp) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(issuer).issuedAt(iat).expiresAt(exp);
        claims.forEach(builder::claim);
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                builder.build())).getTokenValue();
    }

    private String sign(Map<String, Object> claims) {
        Instant now = Instant.now();
        return sign(jwtEncoder, claims, jwtProperties.issuer(), now, now.plusSeconds(600));
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("1-2. login emite tv igual ao token_version e o token é aceite com os papéis da BD")
    void loginIssuesTvAndTokenIsAccepted() throws Exception {
        JsonNode body = loginBody("admin@cred.test", ADMIN_PASSWORD);
        String token = body.get("accessToken").asText();
        assertThat(body.get("mustChangePassword").asBoolean()).isFalse();
        assertThat(jwtDecoder.decode(token).getClaims().get("tv")).isEqualTo(0L);
        assertThat(jwtDecoder.decode(token).getClaimAsString("token_type")).isEqualTo("ORG_USER");

        getWith(token, ME).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
        getWith(token, ADMIN_ONLY).andExpect(status().isOk());
    }

    @Test
    @DisplayName("3. tv antigo (token_version incrementado) → 401")
    void staleTvIsRejected() throws Exception {
        String token = login("admin@cred.test", ADMIN_PASSWORD);
        User user = reload(admin);
        user.revokeSessions();
        userRepository.save(user);
        getWith(token, ME).andExpect(status().isUnauthorized());
        getWith(token, ADMIN_ONLY).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("4/21-legacy. token staff sem claim tv (formato anterior à V17) → 401")
    void missingTvIsRejected() throws Exception {
        Map<String, Object> claims = staffClaims(admin, org.getId());
        claims.remove("tv");
        getWith(sign(claims), ME).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("5. tv string, fraccionário, negativo ou fora de int → 401")
    void malformedTvIsRejected() throws Exception {
        for (Object tv : List.of("0", 0.5d, -1, 4_294_967_296L, true)) {
            Map<String, Object> claims = staffClaims(admin, org.getId());
            claims.put("tv", tv);
            getWith(sign(claims), ME).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("6. token expirado → 401")
    void expiredTokenIsRejected() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        String token = sign(jwtEncoder, staffClaims(admin, org.getId()), jwtProperties.issuer(),
                past, past.plusSeconds(600));
        getWith(token, ME).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("7. token adulterado ou assinado com outra chave → 401")
    void tamperedOrForeignKeyTokenIsRejected() throws Exception {
        String token = login("author@cred.test", AUTHOR_PASSWORD);
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"AUTHOR\"", "\"ADMIN\"");
        String tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        getWith(tampered, ME).andExpect(status().isUnauthorized());

        byte[] foreignKey = "a-completely-different-signing-key-0123456789".getBytes(StandardCharsets.UTF_8);
        JwtEncoder foreign = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(foreignKey, "HmacSHA256")));
        Instant now = Instant.now();
        getWith(sign(foreign, staffClaims(admin, org.getId()), jwtProperties.issuer(), now, now.plusSeconds(600)), ME)
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token correctamente assinado sem exp ou sem sub → 401")
    void missingStandardClaimsAreRejected() throws Exception {
        Instant now = Instant.now();
        Map<String, Object> claims = staffClaims(admin, org.getId());
        String withoutExp = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                build(claims, now, null))).getTokenValue();
        Map<String, Object> noSub = new HashMap<>(claims);
        noSub.remove("sub");
        String withoutSub = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                build(noSub, now, now.plusSeconds(600)))).getTokenValue();
        getWith(withoutExp, ME).andExpect(status().isUnauthorized());
        getWith(withoutSub, ME).andExpect(status().isUnauthorized());
        // Control: the same claims with exp and sub are accepted.
        getWith(jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                build(claims, now, now.plusSeconds(600)))).getTokenValue(), ME).andExpect(status().isOk());
    }

    private JwtClaimsSet build(Map<String, Object> claims, Instant iat, Instant exp) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuer(jwtProperties.issuer());
        if (iat != null) builder.issuedAt(iat);
        if (exp != null) builder.expiresAt(exp);
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    @DisplayName("8. issuer errado → 401")
    void wrongIssuerIsRejected() throws Exception {
        Instant now = Instant.now();
        getWith(sign(jwtEncoder, staffClaims(admin, org.getId()), "another-issuer", now, now.plusSeconds(600)), ME)
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token_type ausente ou desconhecido → 401")
    void unknownTokenTypeIsRejected() throws Exception {
        Map<String, Object> missing = staffClaims(admin, org.getId());
        missing.remove("token_type");
        getWith(sign(missing), ME).andExpect(status().isUnauthorized());
        Map<String, Object> unknown = staffClaims(admin, org.getId());
        unknown.put("token_type", "SERVICE");
        getWith(sign(unknown), ME).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("9. token CLIENT_PORTAL não entra em rotas staff (incl. /auth/me); ORG_USER não entra no portal")
    void tokenTypeBoundary() throws Exception {
        CommercialPlan plan = commercialPlanRepository.save(
                new CommercialPlan("Unlimited", PlanType.MONTHLY, null, null, null));
        organizationPlanRepository.save(new OrganizationPlan(org.getId(), plan, Instant.now().minusSeconds(60), null));
        ClientDetailResponse client = clientService.create(org.getId(), admin.getId(),
                new ClientCreateRequest("Empresa Portal", null, null, null, null,
                        null, null, null, null, null, null, null, null));
        clientPortalUserService.create(org.getId(), client.id(),
                new ClientPortalUserCreateRequest("cliente@portal.test", "portal-password-123"));

        String portalBody = mockMvc.perform(post("/api/v1/client-auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("organizationId", org.getId().toString(),
                                "email", "cliente@portal.test", "password", "portal-password-123"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String portalToken = objectMapper.readTree(portalBody).get("accessToken").asText();

        // Portal keeps working (regression) …
        getWith(portalToken, PORTAL_ME).andExpect(status().isOk());
        // … but can never act as staff.
        getWith(portalToken, ME).andExpect(status().isForbidden());
        getWith(portalToken, ADMIN_ONLY).andExpect(status().isForbidden());
        getWith(portalToken, STAFF_ANY_ROLE).andExpect(status().isForbidden());
        postWith(portalToken, "/api/v1/auth/logout-all", null).andExpect(status().isForbidden());

        // A staff token is not a portal token.
        String staffToken = login("admin@cred.test", ADMIN_PASSWORD);
        getWith(staffToken, PORTAL_ME).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("10. utilizador inexistente → 401")
    void unknownUserIsRejected() throws Exception {
        Map<String, Object> claims = staffClaims(admin, org.getId());
        claims.put("sub", UUID.randomUUID().toString());
        claims.put("tv", 0);
        getWith(sign(claims), ME).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("11. utilizador DISABLED → token existente 401 e novo login 401")
    void disabledUserIsRejected() throws Exception {
        String token = login("author@cred.test", AUTHOR_PASSWORD);
        User user = reload(author);
        user.disable();
        userRepository.save(user);
        getWith(token, ME).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "author@cred.test", "password", AUTHOR_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("12. utilizador apagado (soft-delete) → 401")
    void deletedUserIsRejected() throws Exception {
        String token = login("author@cred.test", AUTHOR_PASSWORD);
        // Even a token that carries the post-delete tv is refused: deletion itself is checked.
        User user = reload(author);
        user.softDelete();
        userRepository.save(user);
        Map<String, Object> claims = staffClaims(author, org.getId());
        getWith(token, ME).andExpect(status().isUnauthorized());
        getWith(sign(claims), ME).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("13. membership inexistente (organização do claim alheia) ou inactiva → 401")
    void membershipIsRequired() throws Exception {
        // Claim pointing at another organization: never trusted.
        getWith(sign(staffClaims(author, otherOrg.getId())), ME).andExpect(status().isUnauthorized());

        String token = login("author@cred.test", AUTHOR_PASSWORD);
        organizationUserRepository.findByUserIdAndDeletedAtIsNull(author.getId()).forEach(m -> {
            m.softDelete();
            organizationUserRepository.save(m);
        });
        getWith(token, ME).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("14. papel removido na BD tem efeito imediato, mesmo com o claim roles=ADMIN")
    void removedRoleTakesEffectImmediately() throws Exception {
        String token = login("admin2@cred.test", SECOND_ADMIN_PASSWORD);
        getWith(token, ADMIN_ONLY).andExpect(status().isOk());

        organizationUserRepository.save(new OrganizationUser(org, reload(secondAdmin),
                roleRepository.findByName(RoleName.VIEWER).orElseThrow()));
        organizationUserRepository.findByUserIdAndDeletedAtIsNull(secondAdmin.getId()).stream()
                .filter(m -> m.getRole().getName() == RoleName.ADMIN)
                .forEach(m -> {
                    m.softDelete();
                    organizationUserRepository.save(m);
                });

        assertThat(jwtDecoder.decode(token).getClaimAsStringList("roles")).containsExactly("ADMIN");
        getWith(token, ADMIN_ONLY).andExpect(status().isForbidden());
        getWith(token, STAFF_ANY_ROLE).andExpect(status().isOk());
        getWith(token, ME).andExpect(jsonPath("$.roles[0]").value("VIEWER"));
    }

    @Test
    @DisplayName("15. papel acrescentado na BD é reconhecido no pedido seguinte")
    void addedRoleIsRecognised() throws Exception {
        String token = login("author@cred.test", AUTHOR_PASSWORD);
        getWith(token, ADMIN_ONLY).andExpect(status().isForbidden());
        organizationUserRepository.save(new OrganizationUser(org, reload(author),
                roleRepository.findByName(RoleName.ADMIN).orElseThrow()));
        getWith(token, ADMIN_ONLY).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sem token → 401; rotas públicas continuam públicas")
    void anonymousAccess() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk());
    }
}
