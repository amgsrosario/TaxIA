package com.knowledgeflow.ingestion.atfaq.pilot;

import com.knowledgeflow.KnowledgeFlowApplication;
import com.knowledgeflow.knowledge.enums.KnowledgeRiskLevel;
import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import com.knowledgeflow.knowledge.enums.KnowledgeTopic;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Deliberately manual entry point for the guarded one-shot pilot <b>operations</b> tooling — Bloco E,
 * E9C-pilot-operations (PROMPT 100), OPÇÃO A.
 *
 * <p>This is <b>not</b> the application's startup class (the pinned {@code start-class} remains
 * {@link KnowledgeFlowApplication}). It boots a <b>non-web</b> Spring context under the {@code pilot}
 * profile, invokes exactly one {@link TaxiaPilotOperations} action against a single explicit target,
 * prints the result, and exits. Nothing runs at normal application startup: normal boot never loads
 * this class. It never uses OAuth2, never authenticates a human, never opens an HTTP port.
 *
 * <p><b>Scope: preparation only.</b> These six actions bring an N=1 candidate up to VALIDATED. They
 * never publish, index, embed or roll back — that is the separate
 * {@link TaxiaPilotGovernedRunnerLauncher}.
 *
 * <p>Usage (one action; every target-scoped action needs an explicit source system + external key):
 * <pre>
 *   TaxiaPilotOperationsLauncher provision-actors
 *   TaxiaPilotOperationsLauncher import-one        --source-system &lt;SYS&gt; --external-key &lt;KEY&gt; --question &lt;text&gt; --technical-answer &lt;text&gt;
 *   TaxiaPilotOperationsLauncher curate-one        --source-system &lt;SYS&gt; --external-key &lt;KEY&gt; [--short-answer &lt;text&gt;] [--technical-answer &lt;text&gt;] [--normalized-question &lt;text&gt;] [--topic &lt;TOPIC&gt;] [--subtopic &lt;text&gt;] [--jurisdiction &lt;text&gt;] [--risk-level &lt;LEVEL&gt;] [--requires-human-validation &lt;true|false&gt;] [--notes &lt;text&gt;]
 *   TaxiaPilotOperationsLauncher add-source-one    --source-system &lt;SYS&gt; --external-key &lt;KEY&gt; --source-type &lt;TYPE&gt; --title &lt;text&gt; [--legal-reference &lt;text&gt;] [--url &lt;http...&gt;] [--notes &lt;text&gt;]
 *   TaxiaPilotOperationsLauncher pending-review-one --source-system &lt;SYS&gt; --external-key &lt;KEY&gt;
 *   TaxiaPilotOperationsLauncher validate-one       --source-system &lt;SYS&gt; --external-key &lt;KEY&gt; --reviewer-name &lt;text&gt;
 * </pre>
 *
 * <p>Exit codes: {@code 0} for a completed action ({@code PROVISIONED}/{@code IMPORTED}/
 * {@code CURATED}/{@code SOURCE_ADDED}/{@code PENDING_REVIEW}/{@code VALIDATED}/{@code NO_CHANGE});
 * {@code 2} for a governed {@code BLOCKED}; {@code 64} for a usage error. A {@code BLOCKED} outcome is
 * a normal, expected interface — never a stack trace.
 *
 * <p><b>Safety.</b> The pilot profile fails closed on the datasource; the operations bean itself
 * re-checks the pilot base before every action. This class performs no direct database access of its
 * own.
 */
public final class TaxiaPilotOperationsLauncher {

    private TaxiaPilotOperationsLauncher() {
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

        int exitCode;
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(KnowledgeFlowApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("pilot")
                .run()) {

            TaxiaPilotOperations ops = ctx.getBean(TaxiaPilotOperations.class);
            PilotOpsResult result;
            try {
                result = dispatch(ops, actionArg, opts);
            } catch (IllegalArgumentException e) {
                System.err.println("Usage error: " + e.getMessage() + "\n\n" + usage());
                System.exit(64);
                return;
            }

            System.out.println(result.render());
            exitCode = result.outcome() == PilotOpsOutcome.BLOCKED ? 2 : 0;
        }

        System.exit(exitCode);
    }

    private static PilotOpsResult dispatch(TaxiaPilotOperations ops, String action, Map<String, String> opts) {
        switch (action) {
            case "provision-actors" -> {
                return ops.provisionActors();
            }
            case "import-one" -> {
                String system = require(opts, "source-system");
                String key = require(opts, "external-key");
                String question = require(opts, "question");
                // The imported answer accepts either flag name; --technical-answer mirrors the
                // frozen candidate vocabulary, --answer is the plain synonym.
                String answer = firstNonBlank(opts.get("answer"), opts.get("technical-answer"));
                if (answer == null) {
                    throw new IllegalArgumentException(
                            "import-one requires --technical-answer (or --answer).");
                }
                return ops.importOne(system, key, question, answer);
            }
            case "curate-one" -> {
                String system = require(opts, "source-system");
                String key = require(opts, "external-key");
                return ops.curateOne(
                        system, key,
                        opts.get("short-answer"),
                        opts.get("technical-answer"),
                        opts.get("normalized-question"),
                        parseTopic(opts.get("topic")),
                        opts.get("subtopic"),
                        opts.get("jurisdiction"),
                        parseRisk(opts.get("risk-level")),
                        parseBoolean(opts.get("requires-human-validation")),
                        opts.get("notes"));
            }
            case "add-source-one" -> {
                String system = require(opts, "source-system");
                String key = require(opts, "external-key");
                KnowledgeSourceType type = parseSourceType(require(opts, "source-type"));
                String title = require(opts, "title");
                return ops.addSourceOne(
                        system, key, type, title,
                        opts.get("legal-reference"), opts.get("url"), opts.get("notes"));
            }
            case "pending-review-one" -> {
                String system = require(opts, "source-system");
                String key = require(opts, "external-key");
                return ops.pendingReviewOne(system, key);
            }
            case "validate-one" -> {
                String system = require(opts, "source-system");
                String key = require(opts, "external-key");
                String reviewer = require(opts, "reviewer-name");
                return ops.validateOne(system, key, reviewer);
            }
            default -> throw new IllegalArgumentException("unknown action '" + action + "'.");
        }
    }

    private static String require(Map<String, String> opts, String name) {
        String value = opts.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required.");
        }
        return value;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static KnowledgeTopic parseTopic(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return KnowledgeTopic.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown --topic '" + raw + "'.");
        }
    }

    private static KnowledgeRiskLevel parseRisk(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return KnowledgeRiskLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown --risk-level '" + raw + "'.");
        }
    }

    private static KnowledgeSourceType parseSourceType(String raw) {
        try {
            return KnowledgeSourceType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown --source-type '" + raw + "'.");
        }
    }

    private static Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toLowerCase();
        if (v.equals("true") || v.equals("1")) return Boolean.TRUE;
        if (v.equals("false") || v.equals("0")) return Boolean.FALSE;
        throw new IllegalArgumentException("--requires-human-validation must be true or false.");
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
                TaxiaPilotOperationsLauncher — guarded one-shot pilot operations tooling (E9C preparation)

                Actions (exactly one action; target-scoped actions need an explicit source system + external key):
                  provision-actors
                  import-one        --source-system <SYS> --external-key <KEY> --question <text> --technical-answer <text>
                  curate-one        --source-system <SYS> --external-key <KEY> [--short-answer <text>] [--technical-answer <text>]
                                    [--normalized-question <text>] [--topic <TOPIC>] [--subtopic <text>] [--jurisdiction <text>]
                                    [--risk-level <LEVEL>] [--requires-human-validation <true|false>] [--notes <text>]
                  add-source-one    --source-system <SYS> --external-key <KEY> --source-type <TYPE> --title <text>
                                    [--legal-reference <text>] [--url <http...>] [--notes <text>]
                  pending-review-one --source-system <SYS> --external-key <KEY>
                  validate-one       --source-system <SYS> --external-key <KEY> --reviewer-name <text>

                Notes:
                  * Prepares an N=1 candidate up to VALIDATED only — it never publishes, indexes or rolls back
                    (that is TaxiaPilotOperationsLauncher's sibling, TaxiaPilotGovernedRunnerLauncher).
                  * --source-system is mandatory and must be a single plain token (e.g. at-faq, taxia-curated);
                    no wildcards, no lists — it is never inferred or defaulted.
                  * curate-one merges: a provided field overrides, an omitted field keeps the current value.
                  * The curation lifecycle is attributed to the existing pilot admin (piloto.admin@taxia.local);
                    it is never created here and its absence fails closed.
                  * runs under the 'pilot' profile against knowledgeflow_pilot only.""";
    }
}
