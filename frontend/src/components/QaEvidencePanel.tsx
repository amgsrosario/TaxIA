import type { KnowledgeQaDetail, KnowledgeSourceType, SourceReference } from "../api/types";
import { RiskBadge } from "./Badges";

// ─────────────────────────────────────────────────────────────────────────────
// QaEvidencePanel — «Evidência documental» de uma Q&A publicada (só leitura).
//
// Mostra apenas o que o backend devolve em KnowledgeQaDetailResponse: fontes,
// risco, validade, revisão e publicação. Não calcula estado de actualidade,
// não apresenta UUID técnicos e não deduz valores ausentes.
// ─────────────────────────────────────────────────────────────────────────────

const NOT_SET = "Não indicado";

const SOURCE_TYPE_LABELS: Record<KnowledgeSourceType, string> = {
  LEGISLATION: "Legislação",
  ADMINISTRATIVE_GUIDANCE: "Orientação administrativa",
  CASE_LAW: "Jurisprudência",
  OFFICIAL_FAQ: "FAQ oficial",
  INTERNAL_OPINION: "Parecer interno",
  ACCOUNTING_STANDARD: "Norma contabilística",
  OTHER: "Outro",
};

function text(value: string | null | undefined): string {
  return value && value.trim() !== "" ? value : NOT_SET;
}

/** LocalDate ("AAAA-MM-DD") → "DD/MM/AAAA", sem passar por Date (evita desvios de fuso). */
function formatDate(value: string | null): string {
  if (!value) return NOT_SET;
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : value;
}

function formatDateTime(value: string | null): string {
  if (!value) return NOT_SET;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? value : d.toLocaleString("pt-PT");
}

/**
 * Ordem estável: o backend não garante ordenação das fontes. Ordena por data de
 * associação, depois título e, em último caso, id (usado só como desempate).
 */
function sortSources(sources: SourceReference[]): SourceReference[] {
  return [...sources].sort(
    (a, b) =>
      a.createdAt.localeCompare(b.createdAt) ||
      a.title.localeCompare(b.title, "pt-PT") ||
      a.id.localeCompare(b.id),
  );
}

function SourceLink({ url }: { url: string | null }) {
  const value = url?.trim() ?? "";
  if (value === "") return <>{NOT_SET}</>;
  // Só http(s) vira ligação; qualquer outro esquema é mostrado como texto.
  if (!/^https?:\/\//i.test(value)) return <>{value}</>;
  return (
    <a href={value} target="_blank" rel="noopener noreferrer">
      {value.length > 60 ? `${value.slice(0, 60)}…` : value}
    </a>
  );
}

export function QaEvidencePanel({ detail }: { detail: KnowledgeQaDetail }) {
  const sources = sortSources(detail.sources);

  return (
    <div className="card">
      <h2>Evidência documental</h2>

      <div className="meta-row" style={{ marginBottom: 14 }}>
        <div className="item"><span>Risco:</span> <RiskBadge risk={detail.riskLevel} /></div>
        <div className="item">
          <span>Revisão humana:</span> {detail.requiresHumanValidation ? "Obrigatória" : "Não obrigatória"}
        </div>
        <div className="item"><span>Válido de:</span> {formatDate(detail.validFrom)}</div>
        <div className="item"><span>Válido até:</span> {formatDate(detail.validTo)}</div>
      </div>
      <div className="meta-row" style={{ marginBottom: 14 }}>
        <div className="item"><span>Revisto por:</span> {text(detail.reviewedBy)}</div>
        <div className="item"><span>Revisto em:</span> {formatDateTime(detail.reviewedAt)}</div>
        <div className="item"><span>Publicado por:</span> {text(detail.publishedBy)}</div>
        <div className="item"><span>Publicado em:</span> {formatDateTime(detail.publishedAt)}</div>
      </div>

      <h3>Fontes ({sources.length})</h3>
      {sources.length === 0 ? (
        <div className="banner warning">Sem fontes documentais associadas.</div>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Tipo</th><th>Título</th><th>Referência legal</th><th>URL</th>
              <th>Válida de</th><th>Válida até</th><th>Notas</th>
            </tr>
          </thead>
          <tbody>
            {sources.map((s) => (
              <tr key={s.id}>
                <td>{SOURCE_TYPE_LABELS[s.sourceType] ?? s.sourceType}</td>
                <td>{text(s.title)}</td>
                <td>{text(s.legalReference)}</td>
                <td><SourceLink url={s.url} /></td>
                <td>{formatDate(s.validFrom)}</td>
                <td>{formatDate(s.validTo)}</td>
                <td className="muted">{text(s.notes)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
