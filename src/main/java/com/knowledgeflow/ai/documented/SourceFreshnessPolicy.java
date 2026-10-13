package com.knowledgeflow.ai.documented;

import com.knowledgeflow.knowledge.enums.KnowledgeSourceType;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Actualidade governada de uma fonte (C8, ADR-008). O estado é sempre CALCULADO a partir de dados
 * governados — nunca escolhido manualmente nem inferido da idade da fonte:
 *
 * <ol>
 *   <li><b>OUTDATED</b>: marcador de revogação/caducidade/substituição/"sem efeito"/desactualização
 *       no título ou referência, ou {@code validTo} da fonte anterior a hoje.</li>
 *   <li>Só fontes elegíveis podem ser CURRENT/STABLE_BUT_OLD: legislação ({@code LEGISLATION}) e
 *       FAQ/orientação oficial ({@code OFFICIAL_FAQ}, {@code ADMINISTRATIVE_GUIDANCE}).</li>
 *   <li>Verificação válida: {@code verifiedAt} e {@code verifiedBy} presentes, não no futuro e, para
 *       legislação, com a versão oficial/consolidada confirmada.</li>
 *   <li><b>CURRENT</b>: verificação válida há no máximo 90 dias (legislação) ou 60 dias (FAQ/orientação).</li>
 *   <li><b>STABLE_BUT_OLD</b>: verificação válida fora da janela CURRENT, matéria estável marcada por
 *       curador com justificação, até 365 dias (legislação) ou 180 dias (FAQ/orientação).</li>
 *   <li><b>UNCERTAIN</b>: tudo o resto (por omissão, fail-safe). Ausência de informação nunca dá CURRENT.</li>
 * </ol>
 * Datas civis em Europe/Lisbon; os dias contam desde o dia da verificação (dia 0) e os limites são
 * inclusivos (90.º dia ainda CURRENT; 91.º não). {@code validTo} igual a hoje ainda é válido (como no
 * RAG). Uma fonte com {@code validFrom} futuro (ainda não em vigor) é UNCERTAIN. Uma verificação com
 * instante posterior ao relógio é inválida (UNCERTAIN). Relógio injectável — nenhuma regra usa {@code LocalDate.now()} directamente.
 */
@Component
public class SourceFreshnessPolicy {

    public static final ZoneId ZONE = ZoneId.of("Europe/Lisbon");

    public static final int LEGISLATION_CURRENT_DAYS = 90;
    public static final int LEGISLATION_STABLE_DAYS = 365;
    public static final int OFFICIAL_GUIDANCE_CURRENT_DAYS = 60;
    public static final int OFFICIAL_GUIDANCE_STABLE_DAYS = 180;

    /** Comparados sem acentos e em minúsculas ("substituída" ≡ "substituida"). */
    private static final Set<String> OUTDATED_MARKERS = Set.of(
            "revogado", "revogada", "caducado", "caducada", "substituido", "substituida",
            "desactualizado", "desactualizada", "desatualizado", "desatualizada", "sem efeito");

    /** Dados governados de uma fonte necessários ao cálculo. */
    public record Facts(
            KnowledgeSourceType sourceType,
            String title,
            String legalReference,
            LocalDate validTo,
            OffsetDateTime verifiedAt,
            boolean verifierRecorded,
            boolean consolidatedVersionConfirmed,
            boolean stableMatter,
            String stableMatterReason,
            boolean stableMatterCuratorRecorded,
            LocalDate validFrom
    ) {

        /** Sem {@code validFrom} (fonte em vigor desde sempre ou data não registada). */
        public Facts(KnowledgeSourceType sourceType, String title, String legalReference, LocalDate validTo,
                OffsetDateTime verifiedAt, boolean verifierRecorded, boolean consolidatedVersionConfirmed,
                boolean stableMatter, String stableMatterReason, boolean stableMatterCuratorRecorded) {
            this(sourceType, title, legalReference, validTo, verifiedAt, verifierRecorded, consolidatedVersionConfirmed,
                    stableMatter, stableMatterReason, stableMatterCuratorRecorded, null);
        }
    }

    /**
     * Resultado: estado, motivo legível (diagnóstico/backoffice), último dia CURRENT e último dia
     * STABLE_BUT_OLD possíveis (quando aplicável), e se a fonte precisa de (re)verificação.
     */
    public record Assessment(FreshnessStatus status, String reason, LocalDate currentUntil, LocalDate stableUntil,
                             boolean revalidationNeeded) {
    }

    private final Clock clock;

    @Autowired
    public SourceFreshnessPolicy() {
        this(Clock.systemUTC());
    }

    public SourceFreshnessPolicy(Clock clock) {
        this.clock = clock;
    }

    /** Hoje, como data civil em Europe/Lisbon. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** Só marcadores textuais (fontes sem metadados governados, ex.: casos DOCUMENT). */
    public static boolean hasOutdatedMarker(String title, String legalReference) {
        String text = java.text.Normalizer.normalize(
                        (title != null ? title : "") + " " + (legalReference != null ? legalReference : ""),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return OUTDATED_MARKERS.stream().anyMatch(text::contains);
    }

    public Assessment assess(Facts f) {
        LocalDate today = today();
        if (hasOutdatedMarker(f.title(), f.legalReference())) {
            return new Assessment(FreshnessStatus.OUTDATED, "marcador de revogação/caducidade/substituição", null, null, true);
        }
        if (f.validTo() != null && f.validTo().isBefore(today)) {
            return new Assessment(FreshnessStatus.OUTDATED, "validTo da fonte expirado em " + f.validTo(), null, null, true);
        }
        if (f.validFrom() != null && f.validFrom().isAfter(today)) {
            return new Assessment(FreshnessStatus.UNCERTAIN, "fonte ainda não em vigor (validFrom " + f.validFrom() + ")",
                    null, null, false);
        }
        Integer currentDays = currentDays(f.sourceType());
        if (currentDays == null) {
            return new Assessment(FreshnessStatus.UNCERTAIN,
                    "tipo de fonte não elegível para CURRENT (" + f.sourceType() + ")", null, null, false);
        }
        int stableDays = f.sourceType() == KnowledgeSourceType.LEGISLATION
                ? LEGISLATION_STABLE_DAYS : OFFICIAL_GUIDANCE_STABLE_DAYS;
        if (f.verifiedAt() == null || !f.verifierRecorded()) {
            return new Assessment(FreshnessStatus.UNCERTAIN, "sem verificação humana estruturada", null, null, true);
        }
        LocalDate verifiedOn = f.verifiedAt().atZoneSameInstant(ZONE).toLocalDate();
        if (f.verifiedAt().toInstant().isAfter(clock.instant())) {
            return new Assessment(FreshnessStatus.UNCERTAIN, "verificação com data futura (inválida)", null, null, true);
        }
        if (f.sourceType() == KnowledgeSourceType.LEGISLATION && !f.consolidatedVersionConfirmed()) {
            return new Assessment(FreshnessStatus.UNCERTAIN,
                    "legislação sem confirmação da versão oficial/consolidada", null, null, true);
        }
        long days = ChronoUnit.DAYS.between(verifiedOn, today);
        LocalDate currentUntil = verifiedOn.plusDays(currentDays);
        LocalDate stableUntil = verifiedOn.plusDays(stableDays);
        if (days <= currentDays) {
            return new Assessment(FreshnessStatus.CURRENT,
                    "verificada em " + verifiedOn + "; CURRENT até " + currentUntil, currentUntil, null, false);
        }
        boolean stableMarked = f.stableMatter() && f.stableMatterCuratorRecorded()
                && f.stableMatterReason() != null && !f.stableMatterReason().isBlank();
        if (stableMarked && days <= stableDays) {
            return new Assessment(FreshnessStatus.STABLE_BUT_OLD,
                    "verificada em " + verifiedOn + " (há " + days + " dias); matéria estável até " + stableUntil,
                    currentUntil, stableUntil, true);
        }
        return new Assessment(FreshnessStatus.UNCERTAIN,
                stableMarked
                        ? "matéria estável fora do limite (verificada há " + days + " dias)"
                        : "verificação fora da janela CURRENT (há " + days + " dias) sem matéria estável",
                currentUntil, stableMarked ? stableUntil : null, true);
    }

    private static Integer currentDays(KnowledgeSourceType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case LEGISLATION -> LEGISLATION_CURRENT_DAYS;
            case OFFICIAL_FAQ, ADMINISTRATIVE_GUIDANCE -> OFFICIAL_GUIDANCE_CURRENT_DAYS;
            default -> null;
        };
    }

    /** Severidade para agregação: OUTDATED > UNCERTAIN > STABLE_BUT_OLD > CURRENT. */
    public static int severity(FreshnessStatus status) {
        if (status == null) {
            return 3; // ausente = tratado como UNCERTAIN (fail-safe)
        }
        return switch (status) {
            case OUTDATED -> 4;
            case UNCERTAIN -> 3;
            case STABLE_BUT_OLD -> 2;
            case CURRENT -> 1;
        };
    }
}
