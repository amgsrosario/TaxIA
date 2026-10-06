package com.knowledgeflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * ADR-006 JWT secret policy and fail-closed startup guard (unit, no Spring context): missing,
 * public default, short and non-random secrets are refused outside the automated test profiles,
 * and always on knowledgeflow_pilot; error messages never contain the value.
 */
class JwtSecretPolicyTest {

    private static final String STRONG = "Q2xhdWRlLXRlc3Qtb25seS1zdHJvbmcta2V5LTAxMjM0NTY3ODk";
    private static final String SHORT = "short-but-distinct-chars-0123";

    private static JwtProperties props(String secret) {
        return new JwtProperties("knowledgeflow-backend", secret, 60);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> provider(DataSource dataSource) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return provider;
    }

    private static DataSource dataSourceNamed(String catalog) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getCatalog()).thenReturn(catalog);
        return dataSource;
    }

    private static MockEnvironment profiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    // ------------------------------------------------------------------ policy

    @Test
    void policyRefusesMissingDefaultShortAndNonRandom() {
        assertThat(JwtSecretPolicy.violation(null)).isPresent();
        assertThat(JwtSecretPolicy.violation("   ")).isPresent();
        assertThat(JwtSecretPolicy.violation(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT)).isPresent();
        assertThat(JwtSecretPolicy.violation(" " + JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT + " ")).isPresent();
        assertThat(JwtSecretPolicy.violation(SHORT)).isPresent();
        assertThat(JwtSecretPolicy.violation("ab".repeat(40))).isPresent();
    }

    @Test
    void publicTestKeysRefusedOutsideTestProfiles() {
        String testKey = "pgtest-only-jwt-signing-key-not-a-secret-0123456789";
        assertThat(JwtSecretPolicy.violation(testKey)).isPresent();
        assertThat(JwtSecretPolicy.violation(testKey, true)).isEmpty();
        assertThatThrownBy(() -> new JwtSecretStartupGuard(props(testKey), profiles("dev"), provider(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test-only")
                .hasMessageNotContaining(testKey);
        assertThatThrownBy(() -> new JwtSecretStartupGuard(props(testKey), profiles("pilot"), provider(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testKeyOnPilotDatabaseOnlyUnderPgtest() throws SQLException {
        String testKey = "test-only-jwt-signing-key-not-a-secret-0123456789";
        assertThatThrownBy(() -> new JwtSecretStartupGuard(props(testKey), profiles("test"),
                provider(dataSourceNamed("knowledgeflow_pilot")))).isInstanceOf(IllegalStateException.class);
        new JwtSecretStartupGuard(props(testKey), profiles("pgtest"), provider(dataSourceNamed("knowledgeflow_pilot")));
    }

    @Test
    void applicationYmlSecretIsAnEnvironmentPlaceholderWithoutDefault() throws Exception {
        String yml = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "resources", "application.yml"));
        assertThat(yml).contains("secret: ${KNOWLEDGEFLOW_JWT_SECRET:}");
        assertThat(yml).doesNotContain(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT);
    }

    @Test
    void shippedResourcesCarryNoJwtSecretLiteral() throws Exception {
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src", "main", "resources"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                assertThat(java.nio.file.Files.readString(file)).as(file.toString()).doesNotContain(JwtSecretPolicy.TEST_KEY_MARKER);
            }
        }
    }

    @Test
    void policyAcceptsStrongSecret() {
        assertThat(JwtSecretPolicy.violation(STRONG)).isEmpty();
    }

    // ------------------------------------------------------------------ guard

    @Test
    void defaultSecretRefusedInDevAndWithoutProfile() {
        for (String[] active : new String[][] {{"dev"}, {}, {"pilot"}}) {
            assertThatThrownBy(() -> new JwtSecretStartupGuard(props(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT),
                    profiles(active), provider(null)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("public development default")
                    .hasMessageNotContaining(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT);
        }
    }

    @Test
    void missingAndShortSecretRefusedWithoutEchoingTheValue() {
        assertThatThrownBy(() -> new JwtSecretStartupGuard(props(""), profiles("dev"), provider(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not set");
        assertThatThrownBy(() -> new JwtSecretStartupGuard(props(SHORT), profiles("dev"), provider(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than 32 bytes")
                .hasMessageNotContaining(SHORT);
    }

    @Test
    void strongSecretAccepted() {
        new JwtSecretStartupGuard(props(STRONG), profiles("dev"), provider(null));
        new JwtSecretStartupGuard(props(STRONG), profiles("pilot"), provider(null));
    }

    @Test
    void testProfilesOnNonPilotDatabaseAreNotEnforced() throws SQLException {
        new JwtSecretStartupGuard(props(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT), profiles("test"),
                provider(dataSourceNamed("knowledgeflow")));
        new JwtSecretStartupGuard(props("x"), profiles("pgtest"), provider(dataSourceNamed("test")));
    }

    @Test
    void pilotDatabaseAlwaysEnforcedEvenUnderTestProfiles() throws SQLException {
        for (String profile : new String[] {"test", "pgtest"}) {
            DataSource pilot = dataSourceNamed("knowledgeflow_pilot");
            assertThatThrownBy(() -> new JwtSecretStartupGuard(props(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT),
                    profiles(profile, "dev"), provider(pilot)))
                    .isInstanceOf(IllegalStateException.class);
            new JwtSecretStartupGuard(props(STRONG), profiles(profile), provider(pilot));
        }
    }

    @Test
    void enforcedProfilesNeverTouchTheDatabase() throws SQLException {
        DataSource dataSource = dataSourceNamed("knowledgeflow_pilot");
        new JwtSecretStartupGuard(props(STRONG), profiles("dev"), provider(dataSource));
        verify(dataSource, never()).getConnection();
    }

    @Test
    void unreadableDatabaseUnderTestProfileFailsClosed() throws SQLException {
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("down"));
        assertThatThrownBy(() -> new JwtSecretStartupGuard(props(JwtSecretPolicy.PUBLIC_DEVELOPMENT_DEFAULT),
                profiles("test"), provider(broken)))
                .isInstanceOf(IllegalStateException.class);
    }
}
