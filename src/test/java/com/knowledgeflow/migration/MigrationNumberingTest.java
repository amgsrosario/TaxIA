package com.knowledgeflow.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Numeração e dependências Flyway da ADR-010-A (ver {@code docs/flyway-numeracao-adr010a.md}).
 * <ul>
 *   <li>As migrações históricas V1 a V20 não mudam: conteúdo fixado por SHA-256.</li>
 *   <li>V21 a V24 ficam reservadas ao conteúdo do ramo do piloto ({@code ac84c04}). Só podem existir no {@code main}
 *       com o nome e o conteúdo exactos do piloto, para que uma base que as tenha aplicado continue válida.</li>
 *   <li>V23 (registo de despesa da autoridade experimental) e V24 (cenários) não entram sem decisão própria.</li>
 *   <li>As migrações novas da ADR-010-A começam em V25. As versões são únicas e o nome segue {@code V<n>__<desc>.sql}.</li>
 * </ul>
 */
class MigrationNumberingTest {

    private static final Pattern NAME = Pattern.compile("V(\\d+)__[a-z0-9_]+\\.sql");

    /** Conteúdo das migrações históricas, igual no {@code main} {@code c8cb736} e no piloto {@code ac84c04}. */
    private static final Map<String, String> HISTORICAL = Map.ofEntries(
            Map.entry("V1__init_schema.sql",
                    "27c7e95168b5614c31aea836c35d218735e61c293765dc2dfb5ef8fef9510422"),
            Map.entry("V2__create_security_schema.sql",
                    "79e9fd933d8993f20b8460ebae3eb69e93c25e025064a2427105d1e8f7af0aba"),
            Map.entry("V3__create_clients.sql",
                    "b6c3d2e976a9c2141d60fe46eacc66796db48b04bcb72d3d2fc42b32a90e71da"),
            Map.entry("V4__create_opinions.sql",
                    "f67a249887704a0a2e9d26c6bf7a49773c212459944551b9bb00c582e16129ac"),
            Map.entry("V5__generalize_opinions_to_knowledge_cases.sql",
                    "0e8d95a8d0ad66b1ee281061b9ea8765cf88b3f074ce231685ac59a359bf852c"),
            Map.entry("V6__create_audit_events.sql",
                    "06ddeb2c7c68f12e9c95f3f011f976b5077def1f58c000f945ad8328ce4fac72"),
            Map.entry("V7__create_knowledge_case_comments.sql",
                    "9478f3cb9524a7c22c30a5469329c8a0bb827ff9e3e5e568b173771a92c36d30"),
            Map.entry("V8__enrich_clients.sql",
                    "24fd291961cd1e14e1f0885ff2bb30a08df3600ea2fa3866f5c3b067d25442e3"),
            Map.entry("V9__create_client_portal_users.sql",
                    "b54b47caf2783bf6d76c45dfeab83a24554070346c008e126c3f9e1cbc0e5c49"),
            Map.entry("V10__create_billing.sql",
                    "e5965fc1849bf5565e940b398531658b502d323a19dd9decf8049f90dbc6ca9d"),
            Map.entry("V11__create_assisted_interactions.sql",
                    "0f73fcde42fba65d91459315b4aaa24c237f987835dd16b2bd07f445f0c07105"),
            Map.entry("V12__portal_interactions_optional_user.sql",
                    "899d1e94662f60cfe5cb673f677458cc4648eb456e33e80d0230b96b85f5dd0f"),
            Map.entry("V13__add_pgvector_and_embeddings.sql",
                    "e24a387d6045adf63d69c01250a96b288b9a12758ce3e97f6d20d94e2c19fd89"),
            Map.entry("V14__create_knowledge_qa.sql",
                    "1a3f214eeb3d2d9ec1f1eb3853c064fe91f333c0e3b2a006434ee5466210b9e5"),
            Map.entry("V15__create_at_faq_ingestion.sql",
                    "d1d58ebe3cf9a102ef308eebd1e0fb8c7b88c9441559606f3e6113c5c699f2c2"),
            Map.entry("V16__create_qa_applicability_exclusions.sql",
                    "2b54e4663cd83890ef300fd7d40d7b444687fab3849bda448534d613e8973cca"),
            Map.entry("V17__add_staff_credential_session_controls.sql",
                    "17e989aa64944ad3df8f2905bbde4da739bb8bc7bd9c898a51e0790ce0323c5b"),
            Map.entry("V18__add_source_freshness_verification.sql",
                    "a465b599531203e38a21c647465a3fb9a4d21a3fd63084adc1f11cb8ff63f65b"),
            Map.entry("V19__add_applicability_rule_kind.sql",
                    "b331e8ec089954bd12b8f38dc280c23a97eef83bd733ac918117a8b0221d3bee"),
            Map.entry("V20__create_research_evidence_knowledge.sql",
                    "e5e5c8640cad78cea06447861e6330f2385da61e61d3e52e7294e77ccd015d61"));

    /** Versões reservadas ao conteúdo do piloto {@code ac84c04}: nome e SHA-256 exactos. */
    private static final Map<Integer, Map.Entry<String, String>> PILOT_RESERVED = Map.of(
            21, Map.entry("V21__research_model_call_provenance.sql",
                    "573973b226f093e2dc0001a46481bcd32fad09c54cd068ee5d2ade0d74dc2979"),
            22, Map.entry("V22__research_entailment_checks.sql",
                    "87de0175f31d70967c3653c082a50313c8a1b1844d8089f26ebe59aada887e42"),
            23, Map.entry("V23__model_spend_ledger.sql",
                    "b7c75286399ffdbe98b7c1aeae454656a45a1f94fd6769b9fb3dfd304775d5f4"),
            24, Map.entry("V24__research_scenario_analyses.sql",
                    "8ce721d3c8fd6b6f864d734876ccf5e74bad8cba6a4cc9daec7f051a7ca9dbcb"));

    /** Reservadas mas excluídas sem decisão própria: autoridade financeira experimental (V23) e cenários (V24). */
    private static final List<Integer> NOT_IMPORTED = List.of(23, 24);

    static final int FIRST_ADR010A_VERSION = 25;

    private static Map<String, String> migrations() throws Exception {
        Map<String, String> out = new TreeMap<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*")) {
            String name = r.getFilename();
            if (name == null) continue;
            out.put(name, sha256(r));
        }
        return out;
    }

    private static String sha256(Resource r) throws IOException, java.security.NoSuchAlgorithmException {
        try (InputStream in = r.getInputStream()) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }
    }

    private static int version(String name) {
        Matcher m = NAME.matcher(name);
        assertThat(m.matches()).as("nome de migração: " + name).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @Test
    void namesFollowTheConventionAndVersionsAreUnique() throws Exception {
        Map<Integer, String> byVersion = new HashMap<>();
        for (String name : migrations().keySet()) {
            String previous = byVersion.put(version(name), name);
            assertThat(previous).as("versão repetida: " + name).isNull();
        }
    }

    @Test
    void historicalMigrationsAreUnchanged() throws Exception {
        Map<String, String> all = migrations();
        HISTORICAL.forEach((name, sha) -> assertThat(all).as(name).containsEntry(name, sha));
    }

    @Test
    void reservedVersionsCarryOnlyThePilotContent() throws Exception {
        for (Map.Entry<String, String> m : migrations().entrySet()) {
            int v = version(m.getKey());
            if (v <= 20) {
                assertThat(HISTORICAL).as("migração histórica nova ou renomeada: " + m.getKey()).containsKey(m.getKey());
            } else if (v < FIRST_ADR010A_VERSION) {
                assertThat(NOT_IMPORTED).as("versão reservada sem decisão: " + m.getKey()).doesNotContain(v);
                assertThat(Map.entry(m.getKey(), m.getValue())).as("V" + v + " só com o conteúdo do piloto")
                        .isEqualTo(PILOT_RESERVED.get(v));
            }
        }
    }

    @Test
    void reservedTableIsConsistent() {
        assertThat(PILOT_RESERVED.keySet()).containsExactlyInAnyOrder(21, 22, 23, 24);
        PILOT_RESERVED.forEach((v, e) -> assertThat(version(e.getKey())).isEqualTo(v));
        assertThat(FIRST_ADR010A_VERSION).isGreaterThan(PILOT_RESERVED.keySet().stream().max(Integer::compare).orElseThrow());
    }
}
