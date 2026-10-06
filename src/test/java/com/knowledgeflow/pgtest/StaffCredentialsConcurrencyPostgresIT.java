package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowledgeflow.common.error.BusinessException;
import com.knowledgeflow.organizations.entity.Organization;
import com.knowledgeflow.organizations.entity.OrganizationUser;
import com.knowledgeflow.organizations.repository.OrganizationRepository;
import com.knowledgeflow.organizations.repository.OrganizationUserRepository;
import com.knowledgeflow.security.AuthenticatedUser;
import com.knowledgeflow.security.StaffSessionVerifier;
import com.knowledgeflow.users.credentials.StaffAccountAdminService;
import com.knowledgeflow.users.credentials.StaffCredentialService;
import com.knowledgeflow.users.entity.User;
import com.knowledgeflow.users.enums.RoleName;
import com.knowledgeflow.users.enums.UserStatus;
import com.knowledgeflow.users.repository.RoleRepository;
import com.knowledgeflow.users.repository.UserRepository;
import com.knowledgeflow.users.repository.UserRowLock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-006 concurrency on real PostgreSQL (row locks): token_version increments are never lost,
 * concurrent resets/changes are serialised, a stale User in the persistence context never writes
 * back an old token_version, revoke/disable/role change take effect on the next request, and
 * two ADMINs disabling each other can never leave the organization without an active ADMIN.
 *
 * Run: mvn verify -Ppgtest -Dit.test=StaffCredentialsConcurrencyPostgresIT
 */
@SpringBootTest
@ActiveProfiles("pgtest")
@Testcontainers
class StaffCredentialsConcurrencyPostgresIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static final int THREADS = 8;
    private static final String PASSWORD = "password-original-123";

    @Autowired StaffAccountAdminService adminService;
    @Autowired StaffCredentialService credentialService;
    @Autowired StaffSessionVerifier verifier;
    @Autowired UserRepository userRepository;
    @Autowired UserRowLock userRowLock;
    @Autowired RoleRepository roleRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OrganizationUserRepository organizationUserRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;

    private Organization org;
    private User adminA;
    private User adminB;
    private User author;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(new Organization("Org Concorrência " + UUID.randomUUID(), null));
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        adminA = staff("a-" + suffix + "@conc.test", RoleName.ADMIN);
        adminB = staff("b-" + suffix + "@conc.test", RoleName.ADMIN);
        author = staff("author-" + suffix + "@conc.test", RoleName.AUTHOR);
    }

    private User staff(String email, RoleName role) {
        User user = userRepository.save(new User(email, email, passwordEncoder.encode(PASSWORD)));
        organizationUserRepository.save(new OrganizationUser(org, user, roleRepository.findByName(role).orElseThrow()));
        return user;
    }

    private AuthenticatedUser actor(User user) {
        return new AuthenticatedUser(user.getId(), org.getId(), user.getEmail(), List.of("ADMIN"));
    }

    private int tv(User user) {
        return jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, user.getId());
    }

    /** Runs the tasks at the same time; returns how many completed without exception. */
    private <T> int race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        int ok = 0;
        for (Future<T> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS);
                ok++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class);
            }
        }
        pool.shutdown();
        return ok;
    }

    @Test
    @DisplayName("revogações concorrentes: nenhum incremento de token_version se perde")
    void concurrentRevocationsNeverLoseIncrements() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                adminService.revokeSessions(actor(adminA), 0L, author.getId(), "corrida");
                return null;
            });
        }
        assertThat(race(tasks)).isEqualTo(THREADS);
        assertThat(tv(author)).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("dois resets concorrentes: ambos serializados, token_version=2, password final é uma delas")
    void concurrentResetsAreSerialised() throws Exception {
        List<Callable<Void>> tasks = List.of(
                () -> { adminService.resetPassword(actor(adminA), 0L, author.getId(), "temporaria-numero-um", "r1"); return null; },
                () -> { adminService.resetPassword(actor(adminB), 0L, author.getId(), "temporaria-numero-dois", "r2"); return null; });
        assertThat(race(tasks)).isEqualTo(2);
        User after = userRepository.findById(author.getId()).orElseThrow();
        assertThat(after.getTokenVersion()).isEqualTo(2);
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(passwordEncoder.matches("temporaria-numero-um", after.getPasswordHash())
                ^ passwordEncoder.matches("temporaria-numero-dois", after.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("mudanças concorrentes da própria password com o mesmo token: só uma vence")
    void concurrentOwnPasswordChangesOnlyOneWins() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String next = "nova-password-concorrente-" + i;
            tasks.add(() -> {
                credentialService.changeOwnPassword(author.getId(), org.getId(), 0, PASSWORD, next);
                return null;
            });
        }
        assertThat(race(tasks)).isEqualTo(1);
        assertThat(tv(author)).isEqualTo(1);
    }

    @Test
    @DisplayName("User obsoleto no contexto de persistência nunca reescreve um token_version antigo")
    void staleEntityNeverWritesBackOldVersion() {
        transactionTemplate.executeWithoutResult(status -> {
            User loaded = userRepository.findById(author.getId()).orElseThrow();
            assertThat(loaded.getTokenVersion()).isZero();
            // Another transaction (own thread, own connection) increments and commits meanwhile.
            otherTransaction(() -> jdbc.update(
                    "UPDATE users SET token_version = token_version + 1 WHERE id = ?", author.getId()));
            User locked = userRowLock.lock(author.getId()).orElseThrow();
            assertThat(locked).isSameAs(loaded);
            assertThat(locked.getTokenVersion()).isEqualTo(1); // refreshed under the lock, not the stale 0
            locked.revokeSessions();
        });
        assertThat(tv(author)).isEqualTo(2);
    }

    private static void otherTransaction(Runnable work) {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            single.submit(work).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            single.shutdown();
        }
    }

    @Test
    @DisplayName("revoke/disable/papéis vs pedido: o pedido seguinte já vê o novo estado")
    void changesTakeEffectOnNextRequest() {
        assertThat(verifier.verify(author.getId(), org.getId(), 0)).isPresent();
        adminService.revokeSessions(actor(adminA), 0L, author.getId(), "r");
        assertThat(verifier.verify(author.getId(), org.getId(), 0)).isEmpty();
        assertThat(verifier.verify(author.getId(), org.getId(), 1)).isPresent();

        adminService.changeRoles(actor(adminA), 0L, author.getId(), EnumSet.of(RoleName.VIEWER), "r");
        assertThat(verifier.verify(author.getId(), org.getId(), 1)).isEmpty();
        assertThat(verifier.verify(author.getId(), org.getId(), 2).orElseThrow().roles()).containsExactly("VIEWER");

        adminService.disable(actor(adminA), 0L, author.getId(), "r");
        assertThat(verifier.verify(author.getId(), org.getId(), 2)).isEmpty();
        assertThat(verifier.verify(author.getId(), org.getId(), 3)).isEmpty();
        adminService.reactivate(actor(adminA), 0L, author.getId(), "r");
        assertThat(verifier.verify(author.getId(), org.getId(), 3)).isEmpty();
        assertThat(verifier.verify(author.getId(), org.getId(), 4)).isPresent();
    }

    @Test
    @DisplayName("dois ADMIN a desactivarem-se mutuamente: fica sempre um ADMIN activo (o perdedor falha no re-check do actor sob lock)")
    void mutualDisableKeepsOneActiveAdmin() throws Exception {
        List<Callable<Void>> tasks = List.of(
                () -> { adminService.disable(actor(adminA), 0L, adminB.getId(), "x"); return null; },
                () -> { adminService.disable(actor(adminB), 0L, adminA.getId(), "x"); return null; });
        assertThat(race(tasks)).isEqualTo(1);
        long activeAdmins = Set.of(adminA, adminB).stream()
                .map(u -> userRepository.findById(u.getId()).orElseThrow())
                .filter(u -> u.getStatus() == UserStatus.ACTIVE).count();
        assertThat(activeAdmins).isEqualTo(1);
    }

    @Test
    @DisplayName("despromoções concorrentes de dois ADMIN: fica sempre um ADMIN (re-check do actor sob lock)")
    void mutualDemotionKeepsOneAdmin() throws Exception {
        List<Callable<Void>> tasks = List.of(
                () -> { adminService.changeRoles(actor(adminA), 0L, adminB.getId(), EnumSet.of(RoleName.VIEWER), "x"); return null; },
                () -> { adminService.changeRoles(actor(adminB), 0L, adminA.getId(), EnumSet.of(RoleName.VIEWER), "x"); return null; });
        assertThat(race(tasks)).isEqualTo(1);
        assertThat(organizationUserRepository.countOtherActiveAdmins(org.getId(), UUID.randomUUID())).isEqualTo(1);
    }
}
