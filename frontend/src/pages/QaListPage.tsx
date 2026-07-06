import { useCallback, useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, listQa } from "../api/client";
import type {
  KnowledgeCurationStatus,
  KnowledgeQaSummary,
  KnowledgeRiskLevel,
  KnowledgeTopic,
} from "../api/types";
import { RiskBadge, StatusBadge } from "../components/Badges";

const STATUSES: KnowledgeCurationStatus[] = [
  "IMPORTED", "PENDING_REVIEW", "VALIDATED", "NEEDS_UPDATE",
  "OUTDATED", "REJECTED", "ARCHIVED",
];
const TOPICS: KnowledgeTopic[] = [
  "IVA", "IRC", "IRS", "SEGURANCA_SOCIAL", "TRABALHO",
  "CONTABILIDADE", "PROCEDIMENTO_TRIBUTARIO", "FATURACAO", "OUTROS",
];
const RISKS: KnowledgeRiskLevel[] = ["LOW", "MEDIUM", "HIGH", "CRITICAL"];

export function QaListPage() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<KnowledgeQaSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Filtros suportados pelo backend (status, topic) — enviados na query.
  const [status, setStatus] = useState<KnowledgeCurationStatus | "">("");
  const [topic, setTopic] = useState<KnowledgeTopic | "">("");
  // Filtros locais (risco, revisão humana, texto) — aplicados no cliente,
  // porque o endpoint de listagem não os expõe (escala do piloto: dezenas).
  const [risk, setRisk] = useState<KnowledgeRiskLevel | "">("");
  const [humanReview, setHumanReview] = useState<"" | "sim" | "nao">("");
  const [text, setText] = useState("");

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    listQa({ status, topic, page: 0, size: 100 })
      .then((page) => setRows(page.content))
      .catch((err) =>
        setError(err instanceof ApiError ? err.message : "Erro ao carregar a lista."),
      )
      .finally(() => setLoading(false));
  }, [status, topic]);

  useEffect(() => {
    load();
  }, [load]);

  const filtered = useMemo(() => {
    const needle = text.trim().toLowerCase();
    return rows.filter((r) => {
      if (risk && r.riskLevel !== risk) return false;
      if (humanReview === "sim" && !r.requiresHumanValidation) return false;
      if (humanReview === "nao" && r.requiresHumanValidation) return false;
      if (needle) {
        const haystack = `${r.externalKey ?? ""} ${r.originalQuestion} ${r.subtopic ?? ""}`.toLowerCase();
        if (!haystack.includes(needle)) return false;
      }
      return true;
    });
  }, [rows, risk, humanReview, text]);

  return (
    <div>
      <div className="toolbar">
        <h1>Conhecimento Q&amp;A</h1>
        <button className="secondary" onClick={load} disabled={loading}>
          {loading ? "A actualizar…" : "Refrescar"}
        </button>
      </div>

      <div className="filters card">
        <div>
          <label>Estado</label>
          <select value={status} onChange={(e) => setStatus(e.target.value as KnowledgeCurationStatus | "")}>
            <option value="">Todos</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </div>
        <div>
          <label>Tema</label>
          <select value={topic} onChange={(e) => setTopic(e.target.value as KnowledgeTopic | "")}>
            <option value="">Todos</option>
            {TOPICS.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>
        <div>
          <label>Risco</label>
          <select value={risk} onChange={(e) => setRisk(e.target.value as KnowledgeRiskLevel | "")}>
            <option value="">Todos</option>
            {RISKS.map((r) => (
              <option key={r} value={r}>{r}</option>
            ))}
          </select>
        </div>
        <div>
          <label>Revisão humana</label>
          <select value={humanReview} onChange={(e) => setHumanReview(e.target.value as "" | "sim" | "nao")}>
            <option value="">Todos</option>
            <option value="sim">Obrigatória</option>
            <option value="nao">Não obrigatória</option>
          </select>
        </div>
        <div className="grow">
          <label>Pesquisa livre (chave, pergunta, subtema)</label>
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder="ex.: IVA acto isolado" />
        </div>
      </div>

      {error && <div className="banner error">{error}</div>}

      {loading ? (
        <div className="empty-state">A carregar…</div>
      ) : filtered.length === 0 ? (
        <div className="empty-state">
          {rows.length === 0
            ? "Sem casos para os filtros do servidor seleccionados."
            : "Nenhum caso corresponde aos filtros locais."}
        </div>
      ) : (
        <div className="card" style={{ padding: 0 }}>
          <table>
            <thead>
              <tr>
                <th>Chave externa</th>
                <th>Estado</th>
                <th>Tema</th>
                <th>Risco</th>
                <th>Rev. humana</th>
                <th>Pergunta</th>
                <th>Actualizado</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id} className="clickable" onClick={() => navigate(`/qa/${r.id}`)}>
                  <td>{r.externalKey ?? "—"}</td>
                  <td><StatusBadge status={r.curationStatus} /></td>
                  <td>{r.topic ?? "—"}{r.subtopic ? ` · ${r.subtopic}` : ""}</td>
                  <td><RiskBadge risk={r.riskLevel} /></td>
                  <td>{r.requiresHumanValidation ? "Sim" : "Não"}</td>
                  <td>
                    {r.originalQuestion.length > 90
                      ? r.originalQuestion.slice(0, 90) + "…"
                      : r.originalQuestion}
                  </td>
                  <td className="muted">{new Date(r.updatedAt).toLocaleDateString("pt-PT")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      <div className="muted">
        {filtered.length} de {rows.length} casos apresentados.
      </div>
    </div>
  );
}
