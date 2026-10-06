package com.knowledgeflow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.support.H2TestDatabaseCleaner;
import com.knowledgeflow.users.entity.Role;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Fixtures for the ADR-006 HTTP tests: real logins and real Bearer tokens through the configured
 * decoder and converter (never the {@code jwt()} mock, which would bypass the per-request checks).
 */
abstract class StaffSecurityHttpTestSupport {

    static final String ADMIN_PASSWORD = "admin-password-original-1";
    static final String SECOND_ADMIN_PASSWORD = "second-admin-password-1";
    static final String AUTHOR_PASSWORD = "author-password-original-1";
    static final String OTHER_ADMIN_PASSWORD = "other-org-admin-password-1";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired DataSource dataSource;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUserRepository organizationUserRepository;

    Organization org;
    Organization otherOrg;
    User admin;
    User secondAdmin;
    User author;
    User otherOrgAdmin;

    @BeforeEach
    void setUpStaff() {
        H2TestDatabaseCleaner.clean(dataSource);
        for (RoleName name : RoleName.values()) {
            roleRepository.findByName(name).orElseGet(() -> roleRepository.save(new Role(name, name.name())));
        }
        org = organizationRepository.save(new Organization("Org Credenciais", null));
        otherOrg = organizationRepository.save(new Organization("Outra Org", null));
        admin = staff(org, "admin@cred.test", "Admin", ADMIN_PASSWORD, RoleName.ADMIN);
        secondAdmin = staff(org, "admin2@cred.test", "Admin Dois", SECOND_ADMIN_PASSWORD, RoleName.ADMIN);
        author = staff(org, "author@cred.test", "Autor", AUTHOR_PASSWORD, RoleName.AUTHOR);
        otherOrgAdmin = staff(otherOrg, "admin@other.test", "Admin Outra", OTHER_ADMIN_PASSWORD, RoleName.ADMIN);
    }

    User staff(Organization organization, String email, String name, String password, RoleName... roles) {
        User user = userRepository.save(new User(email, name, passwordEncoder.encode(password)));
        for (RoleName role : roles) {
            organizationUserRepository.save(new OrganizationUser(organization, user,
                    roleRepository.findByName(role).orElseThrow()));
        }
        return user;
    }

    User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    JsonNode loginBody(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    String login(String email, String password) throws Exception {
        return loginBody(email, password).get("accessToken").asText();
    }

    ResultActions getWith(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    ResultActions postWith(String token, String path, Object body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mockMvc.perform(request);
    }

    ResultActions putWith(String token, String path, Object body) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    static String adminUsers(UUID id, String action) {
        return "/api/v1/admin/users/" + id + "/" + action;
    }
}
