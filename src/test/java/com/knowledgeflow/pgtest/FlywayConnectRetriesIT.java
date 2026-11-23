package com.knowledgeflow.pgtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.internal.exception.FlywaySqlException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ensaio delimitado de {@code spring.flyway.connect-retries} no perfil {@code pgtest} (ordem executiva de 23/11,
 * ponto 5). Um proxy TCP entre o Flyway e um PostgreSQL descartável recusa (fecha) as primeiras K ligações e
 * encaminha as seguintes; conta as tentativas efectivamente observadas. Os valores de tentativas e de intervalo são
 * lidos do próprio {@code application-pgtest.yml}. Cenários: sem tentativas (situação anterior), indisponibilidade
 * transitória (recupera) e persistente (falha explícita depois de esgotadas as tentativas). As medições ficam em
 * {@code target/flyway-connect-retries-ensaio.txt}.
 * Run: mvn verify -Ppgtest -Dit.test=FlywayConnectRetriesIT
 */
@Testcontainers
class FlywayConnectRetriesIT {

    @Container
    static final PostgreSQLContainer<?> postgres = PgContainers.pgvector();

    static int retries;
    static Duration interval;
    static final StringBuilder report = new StringBuilder();

    @BeforeAll
    static void readProfile() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("pgtest", new ClassPathResource("application-pgtest.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        Binder binder = Binder.get(env);
        retries = binder.bind("spring.flyway.connect-retries", Integer.class).orElse(0);
        interval = binder.bind("spring.flyway.connect-retries-interval", Duration.class).orElse(Duration.ofSeconds(120));
        report.append("Perfil pgtest: connect-retries=").append(retries).append(", connect-retries-interval=")
                .append(interval).append('\n');
    }

    @AfterAll
    static void writeReport() throws IOException {
        Files.writeString(Path.of("target/flyway-connect-retries-ensaio.txt"), report.toString());
    }

    /** Proxy que fecha as primeiras {@code reject} ligações e encaminha as restantes para o PostgreSQL. */
    static final class FlakyProxy implements AutoCloseable {
        final ServerSocket server;
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();

        FlakyProxy(int reject) throws IOException {
            server = new ServerSocket();
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            Thread t = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        int n = accepted.incrementAndGet();
                        if (n <= reject) {
                            rejected.incrementAndGet();
                            client.close();
                            continue;
                        }
                        Socket upstream = new Socket(postgres.getHost(), postgres.getMappedPort(5432));
                        pipe(client.getInputStream(), upstream.getOutputStream());
                        pipe(upstream.getInputStream(), client.getOutputStream());
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "jdbc:postgresql://127.0.0.1:" + server.getLocalPort() + "/" + postgres.getDatabaseName();
        }

        private static void pipe(InputStream in, OutputStream out) {
            Thread t = new Thread(() -> {
                try (in; out) {
                    in.transferTo(out);
                } catch (IOException ignored) {
                    // fim da ligação
                }
            });
            t.setDaemon(true);
            t.start();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static Flyway flyway(FlakyProxy proxy, int connectRetries) {
        return Flyway.configure().dataSource(proxy.url(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").connectRetries(connectRetries)
                .connectRetriesInterval((int) interval.toSeconds()).cleanDisabled(true).load();
    }

    @Test
    void profileConfiguresBoundedRetries() {
        assertThat(retries).isEqualTo(5);
        assertThat(interval).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void withoutRetriesAOneOffRefusalFailsImmediately() throws IOException {
        try (FlakyProxy proxy = new FlakyProxy(1)) {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> flyway(proxy, 0).validate()).isInstanceOf(FlywaySqlException.class)
                    .hasMessageContaining("Unable to obtain connection");
            report.append(String.format("Situação anterior (0 tentativas), 1 recusa: FALHA após %d tentativa(s), %.1f s%n",
                    proxy.rejected.get(), (System.nanoTime() - t0) / 1e9));
            assertThat(proxy.rejected.get()).isEqualTo(1);
        }
    }

    @Test
    void transientRefusalsWithinTheRetriesRecover() throws IOException {
        try (FlakyProxy proxy = new FlakyProxy(2)) {
            long t0 = System.nanoTime();
            var result = flyway(proxy, retries).migrate();
            report.append(String.format("Transitória (%d tentativas configuradas), 2 recusas: SUCESSO (%d migrações) após %d "
                    + "recusas observadas, %.1f s%n", retries, result.migrationsExecuted, proxy.rejected.get(),
                    (System.nanoTime() - t0) / 1e9));
            assertThat(proxy.rejected.get()).isEqualTo(2);
            assertThat(result.success).isTrue();
        }
    }

    @Test
    void persistentRefusalStillFailsExplicitlyAfterTheRetries() throws IOException {
        try (FlakyProxy proxy = new FlakyProxy(Integer.MAX_VALUE)) {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> flyway(proxy, retries).validate()).isInstanceOf(FlywaySqlException.class)
                    .hasMessageContaining("Unable to obtain connection");
            double secs = (System.nanoTime() - t0) / 1e9;
            report.append(String.format("Persistente (%d tentativas configuradas): FALHA EXPLÍCITA após %d tentativas "
                    + "observadas, %.1f s%n", retries, proxy.rejected.get(), secs));
            assertThat(proxy.rejected.get()).isEqualTo(retries + 1);
        }
    }
}
