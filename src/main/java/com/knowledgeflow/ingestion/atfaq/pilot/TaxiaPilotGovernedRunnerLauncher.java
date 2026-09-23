package com.knowledgeflow.ingestion.atfaq.pilot;

import com.knowledgeflow.KnowledgeFlowApplication;
import com.knowledgeflow.ingestion.atfaq.batch.AtFaqRollbackMotive;
import com.knowledgeflow.ingestion.atfaq.batch.AtFaqRollbackReason;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Deliberately manual entry point for the guarded one-shot pilot runner — Bloco E, E9C-pilot-runner
 * (PROMPT 93), OPÇÃO B.
 *
 * <p>This is <b>not</b> the application's startup class (the pinned {@code start-class} remains
 * {@link KnowledgeFlowApplication}). It boots a <b>non-web</b> Spring context under the {@code pilot}
 * profile, invokes exactly one {@link TaxiaPilotGovernedRunner} action against a single explicit
 * target, prints the result, and exits. Nothing runs at normal application startup: normal boot never
 * loads this class.
 *
 * <p>Usage (one action, one explicit external key):
 * <pre>
 *   TaxiaPilotGovernedRunnerLauncher status       --external-key &lt;KEY&gt;
 *   TaxiaPilotGovernedRunnerLauncher publish-one  --external-key &lt;KEY&gt;
 *   TaxiaPilotGovernedRunnerLauncher rollback-one --external-key &lt;KEY&gt; --reason-code &lt;CODE&gt; [--reason-detail &lt;text&gt;]
 * </pre>
 *
 * <p>Exit codes: {@code 0} for a completed action ({@code READY}/{@code PUBLISHED}/
 * {@code ROLLED_BACK}/{@code NO_CHANGE}); {@code 2} for a governed {@code BLOCKED}; {@code 64} for a
 * usage error. A {@code BLOCKED} outcome is a normal, expected interface — never a stack trace.
 *
 * <p><b>Safety.</b> The pilot profile fails closed on datasource and AI decisions; the runner itself
 * re-checks the pilot base and, for writes, requires {@code AT_FAQ_E9C_PILOT_ENABLED=true}. This
 * class performs no direct database access of its own.
 */
public final class TaxiaPilotGovernedRunnerLauncher {

    private TaxiaPilotGovernedRunnerLauncher() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println(usage());
            System.exit(64);
            return;
        }

        String actionArg = args[0].trim().toLowerCase();
        Map<String, String> opts;
        try {
            opts = parseOptions(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Usage error: " + e.getMessage() + "\n\n" + usage());
            System.exit(64);
            return;
        }

        String externalKey = opts.get("external-key");
        if (externalKey == null || externalKey.isBlank()) {
            System.err.println("Usage error: --external-key is required.\n\n" + usage());
            System.exit(64);
            return;
        }

        int exitCode;
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(KnowledgeFlowApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("pilot")
                .run()) {

            TaxiaPilotGovernedRunner runner = ctx.getBean(TaxiaPilotGovernedRunner.class);
            PilotRunnerResult result;

            switch (actionArg) {
                case "status" -> result = runner.status(externalKey);
                case "publish-one" -> result = runner.publishOne(externalKey);
                case "rollback-one" -> {
                    AtFaqRollbackMotive motive;
                    try {
                        motive = buildMotive(opts);
                    } catch (IllegalArgumentException e) {
                        System.err.println("Usage error: " + e.getMessage() + "\n\n" + usage());
                        System.exit(64);
                        return;
                    }
                    result = runner.rollbackOne(externalKey, motive);
                }
                default -> {
                    System.err.println("Usage error: unknown action '" + actionArg + "'.\n\n" + usage());
                    System.exit(64);
                    return;
                }
            }

            System.out.println(result.render());
            exitCode = result.outcome() == PilotRunnerOutcome.BLOCKED ? 2 : 0;
        }

        System.exit(exitCode);
    }

    private static AtFaqRollbackMotive buildMotive(Map<String, String> opts) {
        String reasonCode = opts.get("reason-code");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("rollback-one requires --reason-code.");
        }
        AtFaqRollbackReason reason;
        try {
            reason = AtFaqRollbackReason.valueOf(reasonCode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown --reason-code '" + reasonCode + "'.");
        }
        // AtFaqRollbackMotive enforces that OTHER requires a non-blank detail.
        return AtFaqRollbackMotive.of(reason, opts.get("reason-detail"));
    }

    /** Parses {@code --key value} pairs from args[1..]. Rejects unknown flag shapes. */
    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new HashMap<>();
        int i = 1;
        while (i < args.length) {
            String token = args[i];
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("expected an option starting with '--' but got '" + token + "'.");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("option '" + token + "' requires a value.");
            }
            opts.put(token.substring(2), args[i + 1]);
            i += 2;
        }
        return opts;
    }

    private static String usage() {
        return """
                TaxiaPilotGovernedRunnerLauncher — guarded one-shot governed pilot runner (E9C)

                Actions (exactly one action, one explicit external key):
                  status       --external-key <KEY>
                  publish-one  --external-key <KEY>
                  rollback-one --external-key <KEY> --reason-code <CODE> [--reason-detail <text>]

                Notes:
                  * status is read-only and may run with AT_FAQ_E9C_PILOT_ENABLED=false.
                  * publish-one / rollback-one require AT_FAQ_E9C_PILOT_ENABLED=true.
                  * runs under the 'pilot' profile against knowledgeflow_pilot only.""";
    }
}
