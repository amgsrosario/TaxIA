package com.knowledgeflow.users.credentials;

import com.knowledgeflow.KnowledgeFlowApplication;
import java.io.Console;
import java.net.InetAddress;
import java.util.Arrays;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Deliberately manual break-glass entry point (ADR-006). Not the application's startup class.
 *
 * <pre>
 *   StaffAdminRecoveryLauncher admin-recover --email &lt;email&gt; --reason "&lt;text&gt;"
 * </pre>
 *
 * <p>Off by default: refuses unless {@code BREAK_GLASS_ENABLED=true} is set for this execution.
 * Boots a non-web context under the {@code pilot} profile (datasource guarded, Flyway disabled: an
 * outdated schema fails Hibernate validation before any write), reads the new
 * temporary password twice from the interactive console without echo (never from an argument,
 * environment variable or file), calls {@link StaffBreakGlassService} once, prints
 * {@code RESULT=RECOVERED|BLOCKED} and exits. Never prints a password, hash, token or secret.
 *
 * <p>Exit codes: {@code 0} RECOVERED, {@code 2} BLOCKED, {@code 64} usage error.
 */
public final class StaffAdminRecoveryLauncher {

    static final String ENABLE_VARIABLE = "BREAK_GLASS_ENABLED";

    private StaffAdminRecoveryLauncher() {
    }

    public static void main(String[] args) {
        String email = null;
        String reason = null;
        if (args.length != 5 || !"admin-recover".equals(args[0])) {
            usage();
            return;
        }
        for (int i = 1; i < args.length; i += 2) {
            switch (args[i]) {
                case "--email" -> email = args[i + 1];
                case "--reason" -> reason = args[i + 1];
                default -> {
                    usage();
                    return;
                }
            }
        }
        if (email == null || reason == null) {
            usage();
            return;
        }
        boolean enabled = breakGlassEnabled(System::getenv);
        if (!enabled) {
            System.out.println(BreakGlassResult.blocked(
                    "break-glass is disabled (" + ENABLE_VARIABLE + " is not true)").render());
            System.exit(2);
            return;
        }
        Console console = System.console();
        if (console == null) {
            System.out.println(BreakGlassResult.blocked(
                    "an interactive console is required to read the password without echo").render());
            System.exit(2);
            return;
        }
        char[] first = console.readPassword("Nova password temporária (mín. 12 caracteres): ");
        char[] second = console.readPassword("Confirmar password: ");
        if (first == null || second == null) {
            System.out.println(BreakGlassResult.blocked("no password read").render());
            System.exit(2);
            return;
        }

        int exitCode;
        try (ConfigurableApplicationContext ctx = boot()) {
            BreakGlassResult result = ctx.getBean(StaffBreakGlassService.class)
                    .recover(enabled, email, reason, new String(first), new String(second), hostName());
            System.out.println(result.render());
            exitCode = result.outcome() == BreakGlassResult.Outcome.RECOVERED ? 0 : 2;
        } finally {
            Arrays.fill(first, '\0');
            Arrays.fill(second, '\0');
        }
        System.exit(exitCode);
    }

    /** Command-line argument: outranks application.yml / application-pilot.yml (default properties do not). */
    static final String NO_MIGRATION_ARG = "--spring.flyway.enabled=false";

    /**
     * Non-web pilot context that never migrates as a side effect: the schema must already be
     * current. With Flyway off, Hibernate's validate refuses to start on an outdated schema,
     * before any write. {@code extraArgs} exist for tests only.
     */
    static ConfigurableApplicationContext boot(String... extraArgs) {
        String[] args = new String[extraArgs.length + 1];
        args[0] = NO_MIGRATION_ARG;
        System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        return new SpringApplicationBuilder(KnowledgeFlowApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("pilot")
                .run(args);
    }

    /** The only switch: {@code BREAK_GLASS_ENABLED} must be exactly {@code true} for this execution. */
    static boolean breakGlassEnabled(java.util.function.Function<String, String> environment) {
        return "true".equals(environment.apply(ENABLE_VARIABLE));
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void usage() {
        System.err.println("""
                StaffAdminRecoveryLauncher — governed break-glass recovery of a pilot ADMIN (ADR-006)

                  admin-recover --email <email> --reason "<text>"

                Requires BREAK_GLASS_ENABLED=true for this execution only, the pilot profile
                environment (datasource knowledgeflow_pilot, KNOWLEDGEFLOW_JWT_SECRET) and an
                interactive console: the temporary password is read twice without echo.""");
        System.exit(64);
    }
}
