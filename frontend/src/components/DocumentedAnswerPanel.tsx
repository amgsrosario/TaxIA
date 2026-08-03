import type {
  AnswerProjection,
  AnswerType,
  FreshnessStatus,
  ParecerRequirement,
  SourceQuality,
  SourceRole,
  VisibleSource,
} from "../api/types";

// ─────────────────────────────────────────────────────────────────────────────
// DocumentedAnswerPanel — apresentação da resposta documentada profissional.
//
// Regra central: "Backend decide. Backend projecta. Frontend mostra."
// Este componente NÃO decide nem recalcula nada: recebe um AnswerProjection já
// projectado pelo backend (por visibilityLevel) e limita-se a apresentá-lo,
// traduzindo enums para rótulos legíveis em português de Portugal.
//
// - RESPOSTA_LIMITE e PEDIDO_DE_PARECER são respostas profissionais legítimas,
//   nunca erros nem falhas.
// - Limitações e a necessidade de parecer nunca são escondidas.
// - Campos internos (sourceCore, hiddenDiagnostics, internalDiagnostics,
//   notesInternal, excerpt, scores, ranking, IDs técnicos) não são apresentados
//   — nem sequer chegam a este componente através dos tipos da vista.
//
// Componente isolado e reutilizável: não está ligado a nenhuma rota nem faz
// chamadas à API (respeita o guard rail do cliente HTTP nesta etapa).
// ─────────────────────────────────────────────────────────────────────────────

const ANSWER_TYPE_LABELS: Record<AnswerType, string> = {
  CONSULTA_DOCUMENTADA: "Consulta documentada",
  CONSULTA_DOCUMENTADA_COM_LIMITACOES: "Consulta documentada com limitações",
  RESPOSTA_LIMITE: "Resposta-limite",
  PEDIDO_DE_PARECER: "Pedido de parecer",
};

const PARECER_LABELS: Record<ParecerRequirement, string> = {
  NONE: "Não especialmente necessário para a finalidade normal desta consulta.",
  SUGGESTED: "Recomendado.",
  REQUIRED: "Necessário antes de adoptar uma conclusão fiscal.",
};

const SOURCE_ROLE_LABELS: Record<SourceRole, string> = {
  PRIMARY: "Fonte principal",
  COMPLEMENTARY: "Fonte complementar",
  DERIVATIVE_REPLICATED: "Fonte derivada/replicada",
};

const SOURCE_QUALITY_LABELS: Record<SourceQuality, string> = {
  STRONG: "Forte",
  ADEQUATE: "Adequada",
  LIMITED: "Limitada",
  WEAK: "Fraca",
};

const FRESHNESS_LABELS: Record<FreshnessStatus, string> = {
  CURRENT: "Actual",
  STABLE_BUT_OLD: "Antiga mas estável",
  UNCERTAIN: "Actualidade não confirmada",
  OUTDATED: "Desactualizada/histórica",
};

/** Só apresentamos ligações http(s) — evita esquemas inseguros ou inesperados. */
function isSafeUrl(url: string | null): url is string {
  return !!url && /^https?:\/\//i.test(url.trim());
}

const NO_ANSWER_BODY =
  "Não existe resposta documentada disponível para apresentação.";

function SourceRow({ source }: { source: VisibleSource }) {
  const tags = [
    source.sourceRole ? SOURCE_ROLE_LABELS[source.sourceRole] : null,
    source.sourceQuality ? `Qualidade: ${SOURCE_QUALITY_LABELS[source.sourceQuality]}` : null,
    source.freshnessStatus ? FRESHNESS_LABELS[source.freshnessStatus] : null,
  ].filter((t): t is string => t !== null);

  return (
    <li className="doc-source">
      <div className="doc-source-title">{source.title ?? "Fonte sem título"}</div>
      {source.legalReference && (
        <div className="doc-source-ref">{source.legalReference}</div>
      )}
      {tags.length > 0 && (
        <div className="doc-source-tags">
          {tags.map((t) => (
            <span key={t} className="badge risk-MEDIUM">{t}</span>
          ))}
        </div>
      )}
      {isSafeUrl(source.url) && (
        <a className="doc-source-url" href={source.url} target="_blank" rel="noreferrer">
          {source.url}
        </a>
      )}
    </li>
  );
}

export function DocumentedAnswerPanel({ projection }: { projection: AnswerProjection }) {
  const typeLabel =
    ANSWER_TYPE_LABELS[projection.visibleAnswerType] ?? projection.visibleAnswerType;
  const parecerLabel =
    PARECER_LABELS[projection.visibleParecerRequirement] ??
    projection.visibleParecerRequirement;
  const body = (projection.visibleAnswer ?? "").trim() || NO_ANSWER_BODY;

  const limitations = projection.visibleLimitations ?? [];
  const warnings = projection.visibleWarnings ?? [];
  const sources = projection.visibleSources ?? [];

  return (
    <div className="card doc-answer">
      {/* A — Cabeçalho: tipo de resposta (já decidido a montante) */}
      <div className="doc-answer-header">
        <span className="badge status-VALIDATED">{typeLabel}</span>
      </div>

      {/* B — Corpo da resposta */}
      <div className="field-block">
        <label>Resposta</label>
        <div className="value">{body}</div>
      </div>

      {/* C — Necessidade de parecer (nunca escondida) */}
      <div className="field-block">
        <label>Parecer especializado</label>
        <div className="value">{parecerLabel}</div>
      </div>

      {/* D — Limitações (nunca escondidas) */}
      {limitations.length > 0 && (
        <div className="field-block">
          <label>Limitações</label>
          <ul className="doc-list">
            {limitations.map((l, i) => (
              <li key={i}>{l}</li>
            ))}
          </ul>
        </div>
      )}

      {/* E — Avisos */}
      {warnings.length > 0 && (
        <div className="field-block">
          <label>Avisos</label>
          <ul className="doc-list">
            {warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      {/* F — Fontes visíveis nesta projecção */}
      <div className="field-block">
        <label>Fontes ({sources.length})</label>
        {sources.length === 0 ? (
          <div className="muted">Sem fontes visíveis nesta projecção.</div>
        ) : (
          <ul className="doc-source-list">
            {sources.map((s, i) => (
              <SourceRow key={i} source={s} />
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
